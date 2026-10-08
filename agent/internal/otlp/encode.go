package otlp

import (
	"encoding/json"
	"fmt"
	"time"

	collogspb "go.opentelemetry.io/proto/otlp/collector/logs/v1"
	coltracepb "go.opentelemetry.io/proto/otlp/collector/trace/v1"
	commonpb "go.opentelemetry.io/proto/otlp/common/v1"
	logspb "go.opentelemetry.io/proto/otlp/logs/v1"
	resourcepb "go.opentelemetry.io/proto/otlp/resource/v1"
	tracepb "go.opentelemetry.io/proto/otlp/trace/v1"
	"google.golang.org/protobuf/proto"
)

// Scope is the instrumentation scope of everything this package emits.
type Scope struct {
	Name    string
	Version string
}

func (s Scope) proto() *commonpb.InstrumentationScope {
	return &commonpb.InstrumentationScope{Name: s.Name, Version: s.Version}
}

// EncodeTraces marshals spans into an ExportTraceServiceRequest.
func EncodeTraces(res []KeyValue, scope Scope, spans []Span) ([]byte, error) {
	pbSpans := make([]*tracepb.Span, 0, len(spans))
	for _, s := range spans {
		ps := &tracepb.Span{
			TraceId:           s.TraceID[:],
			SpanId:            s.SpanID[:],
			Name:              s.Name,
			Kind:              tracepb.Span_SPAN_KIND_INTERNAL,
			StartTimeUnixNano: unixNano(s.Start),
			EndTimeUnixNano:   unixNano(s.End),
			Attributes:        attrs(s.Attributes),
		}
		if !s.ParentSpanID.IsZero() {
			ps.ParentSpanId = s.ParentSpanID[:]
		}
		for _, l := range s.Links {
			ps.Links = append(ps.Links, &tracepb.Span_Link{TraceId: l.TraceID[:], SpanId: l.SpanID[:]})
		}
		if s.Error {
			ps.Status = &tracepb.Status{Code: tracepb.Status_STATUS_CODE_ERROR, Message: s.StatusMessage}
		}
		pbSpans = append(pbSpans, ps)
	}
	req := &coltracepb.ExportTraceServiceRequest{
		ResourceSpans: []*tracepb.ResourceSpans{{
			Resource:   &resourcepb.Resource{Attributes: attrs(res)},
			ScopeSpans: []*tracepb.ScopeSpans{{Scope: scope.proto(), Spans: pbSpans}},
		}},
	}
	return proto.Marshal(req)
}

// EncodeLogs marshals log records into an ExportLogsServiceRequest.
func EncodeLogs(res []KeyValue, scope Scope, logs []LogRecord) ([]byte, error) {
	pbLogs := make([]*logspb.LogRecord, 0, len(logs))
	for _, l := range logs {
		kvs := l.Attributes
		if l.EventName != "" {
			kvs = append([]KeyValue{String("event.name", l.EventName)}, kvs...)
		}
		pl := &logspb.LogRecord{
			TimeUnixNano:         unixNano(l.Time),
			ObservedTimeUnixNano: unixNano(time.Now()),
			SeverityNumber:       logspb.SeverityNumber(l.Severity),
			SeverityText:         severityText(l.Severity),
			Body:                 &commonpb.AnyValue{Value: &commonpb.AnyValue_StringValue{StringValue: l.Body}},
			Attributes:           attrs(kvs),
			EventName:            l.EventName,
		}
		if !l.TraceID.IsZero() {
			pl.TraceId = l.TraceID[:]
			pl.SpanId = l.SpanID[:]
		}
		pbLogs = append(pbLogs, pl)
	}
	req := &collogspb.ExportLogsServiceRequest{
		ResourceLogs: []*logspb.ResourceLogs{{
			Resource:  &resourcepb.Resource{Attributes: attrs(res)},
			ScopeLogs: []*logspb.ScopeLogs{{Scope: scope.proto(), LogRecords: pbLogs}},
		}},
	}
	return proto.Marshal(req)
}

func severityText(s Severity) string {
	switch {
	case s >= SeverityError:
		return "ERROR"
	case s >= SeverityWarn:
		return "WARN"
	case s >= SeverityInfo:
		return "INFO"
	default:
		return "DEBUG"
	}
}

func unixNano(t time.Time) uint64 {
	if t.IsZero() {
		return 0
	}
	return uint64(t.UnixNano())
}

func attrs(kvs []KeyValue) []*commonpb.KeyValue {
	out := make([]*commonpb.KeyValue, 0, len(kvs))
	for _, kv := range kvs {
		out = append(out, &commonpb.KeyValue{Key: kv.Key, Value: anyValue(kv.Value)})
	}
	return out
}

func anyValue(v any) *commonpb.AnyValue {
	switch v := v.(type) {
	case string:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_StringValue{StringValue: v}}
	case bool:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_BoolValue{BoolValue: v}}
	case int:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_IntValue{IntValue: int64(v)}}
	case int64:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_IntValue{IntValue: v}}
	case float64:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_DoubleValue{DoubleValue: v}}
	case json.Number:
		// Values restored from a JSON checkpoint decoded with UseNumber.
		if i, err := v.Int64(); err == nil {
			return &commonpb.AnyValue{Value: &commonpb.AnyValue_IntValue{IntValue: i}}
		}
		f, _ := v.Float64()
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_DoubleValue{DoubleValue: f}}
	default:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_StringValue{StringValue: fmt.Sprint(v)}}
	}
}
