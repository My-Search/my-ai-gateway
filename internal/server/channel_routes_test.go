package server

import (
	"reflect"
	"testing"
)

func TestDiffModelNames(t *testing.T) {
	tests := []struct {
		name          string
		before, after map[string]bool
		wantAdded     []string
		wantRemoved   []string
	}{
		{
			name:   "no change",
			before: map[string]bool{"a": true, "b": true},
			after:  map[string]bool{"a": true, "b": true},
			// 空结果必须是非 nil 切片，保证 JSON 序列化为 [] 而非 null
			wantAdded:   []string{},
			wantRemoved: []string{},
		},
		{
			name:        "added only",
			before:      map[string]bool{"a": true},
			after:       map[string]bool{"a": true, "b": true, "c": true},
			wantAdded:   []string{"b", "c"},
			wantRemoved: []string{},
		},
		{
			name:        "removed only",
			before:      map[string]bool{"a": true, "b": true},
			after:       map[string]bool{"a": true},
			wantAdded:   []string{},
			wantRemoved: []string{"b"},
		},
		{
			name:        "added and removed sorted",
			before:      map[string]bool{"z": true, "m": true, "keep": true},
			after:       map[string]bool{"keep": true, "beta": true, "alpha": true},
			wantAdded:   []string{"alpha", "beta"},
			wantRemoved: []string{"m", "z"},
		},
		{
			name:        "empty before to populated after",
			before:      map[string]bool{},
			after:       map[string]bool{"only": true},
			wantAdded:   []string{"only"},
			wantRemoved: []string{},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			added, removed := diffModelNames(tt.before, tt.after)
			if !reflect.DeepEqual(added, tt.wantAdded) {
				t.Errorf("added = %v, want %v", added, tt.wantAdded)
			}
			if !reflect.DeepEqual(removed, tt.wantRemoved) {
				t.Errorf("removed = %v, want %v", removed, tt.wantRemoved)
			}
		})
	}
}
