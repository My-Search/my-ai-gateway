// Package metrics registers the Prometheus metrics the Java build exposed via
// Micrometer, keeping the same metric and label names.
package metrics

import (
	"regexp"
	"sync"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
)

// Registry wraps the Prometheus registry along with the metric families.
type Registry struct {
	Reg              *prometheus.Registry
	RequestsTotal    *prometheus.CounterVec
	RouteLatency     *prometheus.HistogramVec
	CircuitBreakSkip *prometheus.CounterVec

	mu       sync.Mutex
	counters map[string]prometheus.Counter
	timers   map[string]prometheus.Observer
}

var (
	reDateSuffix  = regexp.MustCompile(`-\d{4}-\d{2}-\d{2}$`)
	reCompactDate = regexp.MustCompile(`-\d{8}$`)
)

// NewRegistry creates the registry with JVM-equivalent process collectors.
func NewRegistry() *Registry {
	reg := prometheus.NewRegistry()
	reg.MustRegister(collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}))
	reg.MustRegister(collectors.NewGoCollector())

	r := &Registry{
		Reg: reg,
		RequestsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "mag_requests_total",
			Help: "Total relayed requests by model, channel and result",
		}, []string{"model", "channel", "result"}),
		RouteLatency: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "mag_route_latency_seconds",
			Help:    "Route latency by model, channel and result",
			Buckets: prometheus.DefBuckets,
		}, []string{"model", "channel", "result"}),
		CircuitBreakSkip: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "mag_circuit_break_skip_total",
			Help: "Candidates skipped because of an open circuit breaker",
		}, []string{"scope"}),
		counters: map[string]prometheus.Counter{},
		timers:   map[string]prometheus.Observer{},
	}
	reg.MustRegister(r.RequestsTotal, r.RouteLatency, r.CircuitBreakSkip)
	return r
}

// NormalizeModelName strips deployment date suffixes, mirroring RelayMetrics.
func NormalizeModelName(name string) string {
	if name == "" {
		return "unknown"
	}
	n := reDateSuffix.ReplaceAllString(name, "")
	n = reCompactDate.ReplaceAllString(n, "")
	if n == "" {
		return "unknown"
	}
	return n
}

// RecordRoute records a successful or failed route.
func (r *Registry) RecordRoute(model, channel, result string, latencyMs int64) {
	if r == nil {
		return
	}
	m := NormalizeModelName(model)
	ch := channel
	if ch == "" {
		ch = "none"
	}
	r.RequestsTotal.WithLabelValues(m, ch, result).Inc()
	r.RouteLatency.WithLabelValues(m, ch, result).Observe(float64(latencyMs) / 1000.0)
}

// RecordCircuitBreakSkip counts a circuit-breaker skip.
func (r *Registry) RecordCircuitBreakSkip(scope string) {
	if r == nil {
		return
	}
	if scope == "" {
		scope = "unknown"
	}
	r.CircuitBreakSkip.WithLabelValues(scope).Inc()
}
