// Package otlp builds OTLP trace and log requests and sends them to an
// OTLP/HTTP endpoint, spooling them to disk while the endpoint is unreachable.
//
// Spans are created by the tracker after the fact, with known start and end
// times, so this package works with plain values instead of the OTel SDK.
package otlp

import (
	"crypto/rand"
	"encoding/hex"
	"time"
)

// TraceID and SpanID are OTLP identifiers.
type (
	TraceID [16]byte
	SpanID  [8]byte
)

// NewTraceID returns a random trace ID.
func NewTraceID() TraceID {
	var id TraceID
	_, _ = rand.Read(id[:])
	return id
}

// NewSpanID returns a random span ID.
func NewSpanID() SpanID {
	var id SpanID
	_, _ = rand.Read(id[:])
	return id
}

func (t TraceID) IsZero() bool                 { return t == TraceID{} }
func (s SpanID) IsZero() bool                  { return s == SpanID{} }
func (t TraceID) String() string               { return hex.EncodeToString(t[:]) }
func (s SpanID) String() string                { return hex.EncodeToString(s[:]) }
func (t TraceID) MarshalText() ([]byte, error) { return []byte(t.String()), nil }
func (s SpanID) MarshalText() ([]byte, error)  { return []byte(s.String()), nil }

func (t *TraceID) UnmarshalText(b []byte) error { _, err := hex.Decode(t[:], b); return err }
func (s *SpanID) UnmarshalText(b []byte) error  { _, err := hex.Decode(s[:], b); return err }

// KeyValue is an attribute. Value must be string, bool, int, int64 or float64.
type KeyValue struct {
	Key   string `json:"k"`
	Value any    `json:"v"`
}

// String, Int, Bool and Float build attributes.
func String(k, v string) KeyValue        { return KeyValue{k, v} }
func Int(k string, v int) KeyValue       { return KeyValue{k, int64(v)} }
func Bool(k string, v bool) KeyValue     { return KeyValue{k, v} }
func Float(k string, v float64) KeyValue { return KeyValue{k, v} }

// Link is a span link.
type Link struct {
	TraceID TraceID `json:"trace_id"`
	SpanID  SpanID  `json:"span_id"`
}

// Span is a finished span.
type Span struct {
	TraceID       TraceID
	SpanID        SpanID
	ParentSpanID  SpanID
	Name          string
	Start         time.Time
	End           time.Time
	Attributes    []KeyValue
	Links         []Link
	Error         bool
	StatusMessage string
}

// Severity is the OTLP log severity number.
type Severity int32

const (
	SeverityDebug Severity = 5
	SeverityInfo  Severity = 9
	SeverityWarn  Severity = 13
	SeverityError Severity = 17
)

// LogRecord is a log record. EventName is also set as the event.name
// attribute so that Loki exposes it as structured metadata.
type LogRecord struct {
	Time       time.Time
	Severity   Severity
	EventName  string
	Body       string
	Attributes []KeyValue
	TraceID    TraceID
	SpanID     SpanID
}
