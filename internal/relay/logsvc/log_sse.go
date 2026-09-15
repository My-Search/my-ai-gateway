// Package logsvc provides request-log writing and SSE broadcasting,
// mirroring the Java RequestLogService + AsyncLogWriter + LogSseService.
package logsvc

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"sync"
	"sync/atomic"

	"github.com/my-search/my-ai-gateway/internal/models"
)

const (
	// subscriberBufSize is the buffer size for each SSE subscriber's channel.
	// When the buffer is full the oldest record is dropped so the latest logs
	// always reach the frontend (same as Java SubscriberQueue cap=512).
	subscriberBufSize = 256

	// publishBufSize is the broadcast channel buffer.
	publishBufSize = 256
)

// LogSseService fans log records out to every connected SSE client.
//
// Architecture (simplified versus the Java CPA-pattern but functionally identical):
//
//	Publish(log) → publishCh → dispatchLoop → subscriber channels → SSE endpoints
//
// Subscribe() returns a read-only channel plus a cancel func.  When the client
// disconnects the cancel func is called, which removes the subscriber and closes
// its channel, terminating the SSE handler's range loop.
type LogSseService struct {
	publishCh    chan *models.RequestLog
	registerCh   chan *subscriberReq
	unregisterCh chan string
	stopCh       chan struct{}
	done         chan struct{}

	mu          sync.RWMutex
	subscribers map[string]chan *models.RequestLog

	subIDCounter atomic.Uint64
}

type subscriberReq struct {
	id string
	ch chan *models.RequestLog
}

// NewLogSseService creates and starts the broadcast dispatcher.
func NewLogSseService() *LogSseService {
	s := &LogSseService{
		publishCh:    make(chan *models.RequestLog, publishBufSize),
		registerCh:   make(chan *subscriberReq, 16),
		unregisterCh: make(chan string, 16),
		stopCh:       make(chan struct{}),
		done:         make(chan struct{}),
		subscribers:  make(map[string]chan *models.RequestLog),
	}
	go s.dispatchLoop()
	return s
}

// Subscribe registers a new SSE subscriber. Returns a channel that delivers
// log records and a cancel function to unsubscribe and close the channel.
func (s *LogSseService) Subscribe() (<-chan *models.RequestLog, func()) {
	id := s.nextID()
	ch := make(chan *models.RequestLog, subscriberBufSize)
	s.registerCh <- &subscriberReq{id: id, ch: ch}
	cancel := func() {
		s.unregisterCh <- id
	}
	return ch, cancel
}

// Publish sends a log record to every connected subscriber.
func (s *LogSseService) Publish(log *models.RequestLog) {
	select {
	case s.publishCh <- log:
	default:
		// Publish channel full — drop to avoid blocking the caller.
		// The DB write has already succeeded, so no data is lost.
	}
}

// Shutdown stops the dispatcher and closes all subscriber channels.
func (s *LogSseService) Shutdown() {
	close(s.stopCh)
	<-s.done
}

// ---------------------------------------------------------------------------
// internal
// ---------------------------------------------------------------------------

func (s *LogSseService) dispatchLoop() {
	for {
		select {
		case log := <-s.publishCh:
			s.fanOut(log)

		case req := <-s.registerCh:
			s.mu.Lock()
			s.subscribers[req.id] = req.ch
			s.mu.Unlock()

		case id := <-s.unregisterCh:
			s.mu.Lock()
			if ch, ok := s.subscribers[id]; ok {
				close(ch)
				delete(s.subscribers, id)
			}
			s.mu.Unlock()

		case <-s.stopCh:
			s.mu.Lock()
			for id, ch := range s.subscribers {
				close(ch)
				delete(s.subscribers, id)
			}
			s.mu.Unlock()
			close(s.done)
			return
		}
	}
}

func (s *LogSseService) fanOut(log *models.RequestLog) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, ch := range s.subscribers {
		select {
		case ch <- log:
		default:
			// Subscriber channel full — drop oldest then retry.
			select {
			case <-ch:
			default:
			}
			select {
			case ch <- log:
			default:
				// Still full after draining one — drop.
			}
		}
	}
}

func (s *LogSseService) nextID() string {
	n := s.subIDCounter.Add(1)
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	return fmt.Sprintf("%s-%d", hex.EncodeToString(b), n)
}