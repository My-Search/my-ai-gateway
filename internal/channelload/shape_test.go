package channelload

import "testing"

// 三种真实上游形态都要能解析
func TestParseCatalogAllShapes(t *testing.T) {
	cases := []struct {
		name  string
		input string
		want  int
	}{
		{
			name: "live /models.json: Record<fullID, meta>",
			input: `{
				"zhipuai/glm-5.3": {"name":"GLM-5.3","limit":{"context":262144,"output":32768},"modalities":{"input":["text","image"],"output":["text"]}},
				"anthropic/claude-sonnet-4-5": {"limit":{"context":200000,"output":64000},"modalities":{"input":["text","image"],"output":["text"]}},
				"zai/glm-4.7": {"limit":{"context":202752,"output":32768}}
			}`,
			want: 3,
		},
		{
			name:  "flat data array",
			input: `{"data":[{"id":"anthropic/claude-opus-4.7-fast","context_length":1000000,"architecture":{"input_modalities":["text","image","file"]}}]}`,
			want:  1,
		},
		{
			name:  "provider map (api.json)",
			input: `{"zhipuai":{"id":"zhipuai","models":{"glm-5.3":{"limit":{"context":262144},"modalities":{"input":["text","image","video"]}}}}}`,
			want:  1,
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			entries, err := ParseCatalog([]byte(c.input))
			if err != nil {
				t.Fatalf("ParseCatalog: %v", err)
			}
			if len(entries) != c.want {
				t.Fatalf("got %d entries, want %d", len(entries), c.want)
			}
			for _, e := range entries {
				t.Logf("  id=%-32s short=%-20s ctx=%d input=%s", e.ModelID, e.ShortID, e.ContextLength, e.InputTypes)
			}
		})
	}
}

// 真实 /models.json 下，qoder/glm-5.3 必须命中 glm-5.3 的条目
func TestLiveShapeMatchesQoderGLM(t *testing.T) {
	useCatalogFile(t, `{
		"zhipuai/glm-5.3": {"limit":{"context":262144},"modalities":{"input":["text","image","video"],"output":["text"]}},
		"zhipuai/glm-5.2": {"limit":{"context":131072},"modalities":{"input":["text"]}}
	}`)

	e := catalogLookup("qoder/glm-5.3")
	if e == nil {
		t.Fatal("qoder/glm-5.3 should match zhipuai/glm-5.3")
	}
	if e.ContextLength != 262144 {
		t.Errorf("ctx = %d, want 262144", e.ContextLength)
	}
	if e.InputTypes != "text,image,video" {
		t.Errorf("input = %q", e.InputTypes)
	}
	if e.ModelID != "zhipuai/glm-5.3" {
		t.Errorf("matched model = %s", e.ModelID)
	}
}
