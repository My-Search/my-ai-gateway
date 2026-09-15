package relay

import (
	"math/rand"
	"sync"
	"sync/atomic"
)

// Balancer selects a candidate from the remaining list.
type Balancer interface {
	Select(candidates []RoutingCandidate, modelID int64) *RoutingCandidate
	MarkFailed(candidate *RoutingCandidate)
	MarkSuccess(candidate *RoutingCandidate)
}

// NewBalancer creates a balancer by strategy name.
func NewBalancer(strategy string) Balancer {
	switch strategy {
	case "random":
		return &RandomBalancer{}
	case "round_robin":
		return &RoundRobinBalancer{counters: make(map[int64]*atomic.Int64)}
	default:
		return &FailoverBalancer{}
	}
}

// FailoverBalancer always picks the first candidate.
type FailoverBalancer struct{}

func (b *FailoverBalancer) Select(candidates []RoutingCandidate, _ int64) *RoutingCandidate {
	if len(candidates) == 0 {
		return nil
	}
	return &candidates[0]
}
func (b *FailoverBalancer) MarkFailed(*RoutingCandidate) {}
func (b *FailoverBalancer) MarkSuccess(*RoutingCandidate) {}

// RoundRobinBalancer cycles through candidates per modelID.
type RoundRobinBalancer struct {
	mu       sync.Mutex
	counters map[int64]*atomic.Int64
}

func (b *RoundRobinBalancer) Select(candidates []RoutingCandidate, modelID int64) *RoutingCandidate {
	if len(candidates) == 0 {
		return nil
	}
	b.mu.Lock()
	counter, ok := b.counters[modelID]
	if !ok {
		counter = &atomic.Int64{}
		b.counters[modelID] = counter
	}
	b.mu.Unlock()

	idx := counter.Add(1) % int64(len(candidates))
	if idx < 0 {
		idx = -idx
	}
	return &candidates[idx]
}
func (b *RoundRobinBalancer) MarkFailed(*RoutingCandidate) {}
func (b *RoundRobinBalancer) MarkSuccess(*RoutingCandidate) {}

// RandomBalancer shuffles and picks first.
type RandomBalancer struct{}

func (b *RandomBalancer) Select(candidates []RoutingCandidate, _ int64) *RoutingCandidate {
	if len(candidates) == 0 {
		return nil
	}
	if len(candidates) > 1 {
		rand.Shuffle(len(candidates), func(i, j int) {
			candidates[i], candidates[j] = candidates[j], candidates[i]
		})
	}
	return &candidates[0]
}
func (b *RandomBalancer) MarkFailed(*RoutingCandidate) {}
func (b *RandomBalancer) MarkSuccess(*RoutingCandidate) {}