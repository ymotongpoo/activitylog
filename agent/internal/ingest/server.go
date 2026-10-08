package ingest

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"
)

// maxSkew bounds how far a client supplied time may be from the receive
// time before it is replaced by the receive time.
const maxSkew = 5 * time.Minute

// Server is the local ingest HTTP API. See docs/design.md.
type Server struct {
	store    *Store
	commands chan<- TerminalEvent
	version  string
	log      *slog.Logger
	now      func() time.Time

	srv *http.Server
}

// NewServer creates a server. Terminal events are also sent to commands
// without blocking; they are dropped if the channel is full.
func NewServer(store *Store, commands chan<- TerminalEvent, version string, log *slog.Logger) *Server {
	s := &Server{store: store, commands: commands, version: version, log: log,
		now: func() time.Time { return time.Now().Round(0) }}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/status", s.status)
	mux.HandleFunc("POST /v1/browser", s.browser)
	mux.HandleFunc("POST /v1/editor", s.editor)
	mux.HandleFunc("POST /v1/terminal", s.terminal)
	s.srv = &http.Server{
		Handler:           s.guard(mux),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
	}
	return s
}

// Handler returns the HTTP handler, for tests.
func (s *Server) Handler() http.Handler { return s.srv.Handler }

// ListenAndServe serves on addr until ctx is done.
func (s *Server) ListenAndServe(ctx context.Context, addr string) error {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	go func() {
		<-ctx.Done()
		sctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		s.srv.Shutdown(sctx)
	}()
	if err := s.srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

// guard rejects requests that could come from web pages or DNS rebinding.
func (s *Server) guard(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.Host)
		if err != nil {
			host = r.Host
		}
		switch strings.Trim(host, "[]") {
		case "127.0.0.1", "localhost", "::1":
		default:
			http.Error(w, "forbidden host", http.StatusForbidden)
			return
		}
		if o := r.Header.Get("Origin"); o != "" && !strings.HasPrefix(o, "chrome-extension://") {
			http.Error(w, "forbidden origin", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) status(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"name": "activitylog-agent", "version": s.version})
}

func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10))
	if err := dec.Decode(v); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return false
	}
	return true
}

// fixTime returns t if it is close to now, and now otherwise.
func (s *Server) fixTime(t time.Time) time.Time {
	now := s.now()
	if t.IsZero() || t.Sub(now).Abs() > maxSkew {
		return now
	}
	return t
}

func (s *Server) browser(w http.ResponseWriter, r *http.Request) {
	var rep BrowserReport
	if !decode(w, r, &rep) {
		return
	}
	rep.Time = s.fixTime(rep.Time)
	if rep.Browser == "" {
		rep.Browser = "other"
	}
	s.store.PutBrowser(rep)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) editor(w http.ResponseWriter, r *http.Request) {
	var rep EditorReport
	if !decode(w, r, &rep) {
		return
	}
	if rep.Editor == "" {
		http.Error(w, "editor is required", http.StatusBadRequest)
		return
	}
	rep.Time = s.fixTime(rep.Time)
	s.store.PutEditor(rep)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) terminal(w http.ResponseWriter, r *http.Request) {
	var ev TerminalEvent
	if !decode(w, r, &ev) {
		return
	}
	switch ev.Event {
	case "start", "end", "cwd":
	default:
		http.Error(w, "unknown event", http.StatusBadRequest)
		return
	}
	if ev.PID == 0 {
		http.Error(w, "pid is required", http.StatusBadRequest)
		return
	}
	ev.Time = s.fixTime(ev.Time)
	s.store.PutTerminal(ev)
	if ev.Event != "cwd" && s.commands != nil {
		select {
		case s.commands <- ev:
		default:
			s.log.Warn("dropping terminal event: queue full")
		}
	}
	w.WriteHeader(http.StatusNoContent)
}
