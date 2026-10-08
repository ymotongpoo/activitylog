package otlp

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Spool stores OTLP request bodies on disk while the endpoint is unreachable.
// File names sort by creation time: <unixnano>-<seq>.<signal>.pb
type Spool struct {
	dir      string
	maxBytes int64

	mu  sync.Mutex
	seq int
}

// SpoolEntry is a spooled request.
type SpoolEntry struct {
	Name   string
	Signal Signal
	Size   int64
}

// NewSpool creates the spool directory if needed.
func NewSpool(dir string, maxBytes int64) (*Spool, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	return &Spool{dir: dir, maxBytes: maxBytes}, nil
}

// Put writes body atomically and evicts the oldest entries beyond maxBytes.
func (s *Spool) Put(sig Signal, body []byte) error {
	s.mu.Lock()
	s.seq++
	name := fmt.Sprintf("%020d-%06d.%s.pb", time.Now().UnixNano(), s.seq%1000000, sig)
	s.mu.Unlock()

	tmp := filepath.Join(s.dir, "."+name+".tmp")
	if err := os.WriteFile(tmp, body, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, filepath.Join(s.dir, name)); err != nil {
		os.Remove(tmp)
		return err
	}
	s.evict()
	return nil
}

// List returns entries oldest first.
func (s *Spool) List() []SpoolEntry {
	des, err := os.ReadDir(s.dir)
	if err != nil {
		return nil
	}
	var out []SpoolEntry
	for _, de := range des {
		name := de.Name()
		if de.IsDir() || strings.HasPrefix(name, ".") || !strings.HasSuffix(name, ".pb") {
			continue
		}
		parts := strings.Split(strings.TrimSuffix(name, ".pb"), ".")
		if len(parts) != 2 {
			continue
		}
		info, err := de.Info()
		if err != nil {
			continue
		}
		out = append(out, SpoolEntry{Name: name, Signal: Signal(parts[1]), Size: info.Size()})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

// Read returns the body of e.
func (s *Spool) Read(e SpoolEntry) ([]byte, error) {
	return os.ReadFile(filepath.Join(s.dir, e.Name))
}

// Remove deletes e.
func (s *Spool) Remove(e SpoolEntry) {
	os.Remove(filepath.Join(s.dir, e.Name))
}

// Size returns the number of entries and their total size.
func (s *Spool) Size() (n int, bytes int64) {
	for _, e := range s.List() {
		n++
		bytes += e.Size
	}
	return n, bytes
}

func (s *Spool) evict() {
	if s.maxBytes <= 0 {
		return
	}
	entries := s.List()
	var total int64
	for _, e := range entries {
		total += e.Size
	}
	for _, e := range entries {
		if total <= s.maxBytes {
			return
		}
		s.Remove(e)
		total -= e.Size
	}
}
