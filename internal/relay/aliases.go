package relay

import (
	"github.com/my-search/my-ai-gateway/internal/store"
)

// DataStore is an alias to store.Store so callers pass *store.Store directly.
type DataStore = *store.Store

// R is an alias to store.Row.
type R = store.Row

// _ ensures the aliases compile.
var _ DataStore
var _ R