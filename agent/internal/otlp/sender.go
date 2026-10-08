package otlp

import (
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"
)

// Signal is the OTLP signal path component.
type Signal string

const (
	Traces Signal = "traces"
	Logs   Signal = "logs"
)

// ErrPermanent is wrapped by errors that will not succeed on retry.
var ErrPermanent = errors.New("permanent export failure")

// Sender posts OTLP/HTTP protobuf requests. Requests that fail with a
// retryable error are written to the spool and replayed later.
type Sender struct {
	endpoint string // without the /v1/<signal> suffix
	headers  map[string]string
	client   *http.Client
	spool    *Spool
	log      *slog.Logger
}

// NewSender creates a sender. spool may be nil.
func NewSender(endpoint string, headers map[string]string, spool *Spool, log *slog.Logger) *Sender {
	return &Sender{
		endpoint: strings.TrimSuffix(endpoint, "/"),
		headers:  headers,
		client:   &http.Client{Timeout: 30 * time.Second},
		spool:    spool,
		log:      log,
	}
}

// Send posts body and spools it on retryable failure. The returned error is
// informational; the data is either spooled or dropped.
func (s *Sender) Send(ctx context.Context, sig Signal, body []byte) error {
	err := s.post(ctx, sig, body)
	if err == nil {
		return nil
	}
	if errors.Is(err, ErrPermanent) {
		s.log.Warn("dropping OTLP request", "signal", sig, "err", err)
		return err
	}
	if s.spool != nil {
		if serr := s.spool.Put(sig, body); serr != nil {
			s.log.Error("failed to spool OTLP request", "signal", sig, "err", serr)
		}
	}
	return err
}

// Replay sends spooled requests oldest first. It stops at the first retryable
// failure so that an offline endpoint is not hammered.
func (s *Sender) Replay(ctx context.Context) {
	if s.spool == nil {
		return
	}
	for _, e := range s.spool.List() {
		if ctx.Err() != nil {
			return
		}
		body, err := s.spool.Read(e)
		if err != nil {
			s.log.Warn("removing unreadable spool file", "file", e.Name, "err", err)
			s.spool.Remove(e)
			continue
		}
		err = s.post(ctx, e.Signal, body)
		if err != nil && !errors.Is(err, ErrPermanent) {
			return
		}
		if err != nil {
			s.log.Warn("dropping spooled OTLP request", "file", e.Name, "err", err)
		}
		s.spool.Remove(e)
	}
}

func (s *Sender) post(ctx context.Context, sig Signal, body []byte) error {
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	if _, err := zw.Write(body); err != nil {
		return err
	}
	if err := zw.Close(); err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.endpoint+"/v1/"+string(sig), &buf)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrPermanent, err)
	}
	req.Header.Set("Content-Type", "application/x-protobuf")
	req.Header.Set("Content-Encoding", "gzip")
	for k, v := range s.headers {
		req.Header.Set(k, v)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	msg, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
	switch {
	case resp.StatusCode/100 == 2:
		return nil
	case resp.StatusCode == http.StatusTooManyRequests, resp.StatusCode == http.StatusRequestTimeout,
		resp.StatusCode/100 == 5:
		return fmt.Errorf("%s: %s", resp.Status, bytes.TrimSpace(msg))
	default:
		return fmt.Errorf("%w: %s: %s", ErrPermanent, resp.Status, bytes.TrimSpace(msg))
	}
}
