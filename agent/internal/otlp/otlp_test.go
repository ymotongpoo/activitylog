package otlp

import (
	"compress/gzip"
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	coltracepb "go.opentelemetry.io/proto/otlp/collector/trace/v1"
	"google.golang.org/protobuf/proto"
)

var discard = slog.New(slog.NewTextHandler(io.Discard, nil))

func TestSenderSpoolsAndReplays(t *testing.T) {
	var fail atomic.Bool
	var got atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if fail.Load() {
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		if r.URL.Path != "/otlp/v1/traces" || r.Header.Get("Authorization") != "Basic x" ||
			r.Header.Get("Content-Encoding") != "gzip" {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		zr, err := gzip.NewReader(r.Body)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		b, _ := io.ReadAll(zr)
		var req coltracepb.ExportTraceServiceRequest
		if err := proto.Unmarshal(b, &req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		got.Add(int32(len(req.ResourceSpans[0].ScopeSpans[0].Spans)))
	}))
	defer srv.Close()

	spool, err := NewSpool(t.TempDir(), 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	s := NewSender(srv.URL+"/otlp/", map[string]string{"Authorization": "Basic x"}, spool, discard)
	body, err := EncodeTraces([]KeyValue{String("service.name", "t")}, Scope{Name: "t"}, []Span{{
		TraceID: NewTraceID(), SpanID: NewSpanID(), Name: "active",
		Start: time.Now().Add(-time.Minute), End: time.Now(), Attributes: []KeyValue{Int("n", 1), Bool("b", true)},
	}})
	if err != nil {
		t.Fatal(err)
	}

	ctx := context.Background()
	fail.Store(true)
	if err := s.Send(ctx, Traces, body); err == nil {
		t.Fatal("send succeeded against a failing server")
	}
	if n, _ := spool.Size(); n != 1 {
		t.Fatalf("spooled = %d, want 1", n)
	}
	s.Replay(ctx)
	if n, _ := spool.Size(); n != 1 {
		t.Fatal("replay removed a request that failed")
	}

	fail.Store(false)
	s.Replay(ctx)
	if n, _ := spool.Size(); n != 0 || got.Load() != 1 {
		t.Fatalf("after replay: spooled=%d received=%d", n, got.Load())
	}
}

func TestSenderDropsPermanentFailures(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
	}))
	defer srv.Close()
	spool, _ := NewSpool(t.TempDir(), 1<<20)
	s := NewSender(srv.URL, nil, spool, discard)
	if err := s.Send(context.Background(), Logs, []byte{}); err == nil {
		t.Fatal("expected error")
	}
	if n, _ := spool.Size(); n != 0 {
		t.Error("permanent failure was spooled")
	}
}

func TestSpoolEviction(t *testing.T) {
	spool, _ := NewSpool(t.TempDir(), 25)
	for i := 0; i < 5; i++ {
		if err := spool.Put(Traces, make([]byte, 10)); err != nil {
			t.Fatal(err)
		}
	}
	n, size := spool.Size()
	if n != 2 || size != 20 {
		t.Errorf("after eviction: n=%d size=%d", n, size)
	}
}
