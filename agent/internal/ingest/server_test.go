package ingest

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func newTestServer() (*Server, *Store, chan TerminalEvent) {
	store := NewStore()
	cmds := make(chan TerminalEvent, 4)
	return NewServer(store, cmds, "test", slog.New(slog.NewTextHandler(io.Discard, nil))), store, cmds
}

func post(t *testing.T, h http.Handler, path, body string, hdr map[string]string) int {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:5610"+path, strings.NewReader(body))
	for k, v := range hdr {
		if k == "Host" {
			req.Host = v
		} else {
			req.Header.Set(k, v)
		}
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec.Code
}

func TestGuard(t *testing.T) {
	s, _, _ := newTestServer()
	body := `{"browser":"chrome","instance":"x","focused":true}`
	cases := []struct {
		hdr  map[string]string
		want int
	}{
		{nil, http.StatusNoContent},
		{map[string]string{"Origin": "chrome-extension://abcdef"}, http.StatusNoContent},
		{map[string]string{"Origin": "https://evil.example"}, http.StatusForbidden},
		{map[string]string{"Host": "evil.example:5610"}, http.StatusForbidden},
		{map[string]string{"Host": "localhost:5610"}, http.StatusNoContent},
		{map[string]string{"Host": "[::1]:5610"}, http.StatusNoContent},
	}
	for _, c := range cases {
		if got := post(t, s.Handler(), "/v1/browser", body, c.hdr); got != c.want {
			t.Errorf("%v: status = %d, want %d", c.hdr, got, c.want)
		}
	}
}

func TestBrowserAndEditorReports(t *testing.T) {
	s, store, _ := newTestServer()
	h := s.Handler()
	post(t, h, "/v1/browser", `{"browser":"chrome","instance":"a","focused":true,"url":"https://a.example/"}`, nil)
	post(t, h, "/v1/browser", `{"browser":"brave","instance":"b","focused":true,"url":"https://b.example/"}`, nil)
	now := time.Now()
	if r, ok := store.FocusedBrowser(now, time.Minute, "chrome"); !ok || r.URL != "https://a.example/" {
		t.Errorf("preferred browser not chosen: %+v", r)
	}
	post(t, h, "/v1/browser", `{"browser":"chrome","instance":"a","focused":false}`, nil)
	if r, ok := store.FocusedBrowser(now, time.Minute, "chrome"); !ok || r.Browser != "brave" {
		t.Errorf("blurred browser still chosen: %+v", r)
	}
	if _, ok := store.FocusedBrowser(now.Add(2*time.Minute), time.Minute, "chrome"); ok {
		t.Error("stale report returned")
	}

	post(t, h, "/v1/editor", `{"editor":"neovim","instance":"1","event":"open","focused":true,"file":"/a.go"}`, nil)
	time.Sleep(10 * time.Millisecond)
	post(t, h, "/v1/editor", `{"editor":"neovim","instance":"1","event":"heartbeat","focused":true,"file":"/a.go"}`, nil)
	r, ok := store.FocusedEditor(time.Now(), time.Minute, func(e string) bool { return e == "neovim" })
	if !ok || !r.LastActivity.Before(r.Time) {
		t.Errorf("heartbeat moved LastActivity: %+v", r)
	}
	if got := post(t, h, "/v1/editor", `{"event":"open"}`, nil); got != http.StatusBadRequest {
		t.Errorf("missing editor: status = %d", got)
	}
}

func TestTerminalEvents(t *testing.T) {
	s, store, cmds := newTestServer()
	h := s.Handler()
	post(t, h, "/v1/terminal", `{"event":"cwd","pid":10,"shell":"zsh","cwd":"/tmp","term_program":"ghostty"}`, nil)
	post(t, h, "/v1/terminal", `{"event":"start","pid":11,"shell":"zsh","cwd":"/src","command":"make","term_program":"vscode","time":"2000-01-01T00:00:00Z"}`, nil)
	if len(cmds) != 1 {
		t.Fatalf("queued commands = %d, want 1", len(cmds))
	}
	ev := <-cmds
	if time.Since(ev.Time) > time.Minute {
		t.Errorf("skewed client time not replaced: %v", ev.Time)
	}
	if sh, ok := store.LatestShell("vscode"); !ok || sh.Cwd != "/tmp" {
		t.Errorf("latest shell = %+v", sh)
	}
	if got := post(t, h, "/v1/terminal", `{"event":"bogus","pid":1}`, nil); got != http.StatusBadRequest {
		t.Errorf("bad event: status = %d", got)
	}
}
