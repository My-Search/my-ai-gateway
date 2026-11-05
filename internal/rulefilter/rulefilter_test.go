package rulefilter

import (
	"testing"
)

// 表达式语义与前端 filterMatch 对齐：普通词 AND / 正则 / 混合。
func TestMatchExpression(t *testing.T) {
	tests := []struct {
		expr string
		text string
		want bool
	}{
		// 普通匹配：所有词都必须包含，不区分大小写
		{"glm 最新模型", "GLM-4.6 最新模型", true},
		{"glm 最新", "glm-4-air", false},
		{"DEEPSEEK v3", "deepseek-ai/DeepSeek-V3 (ch)", true},
		// 正则匹配：默认忽略大小写
		{"/AI|人工智能/", "deepseek-v3 (AI 站)", true},
		{"/AI|人工智能/", "gpt-4o (foreign)", false},
		// 混合匹配：正则 + 普通词同时命中
		{"/AI|人工智能/ 资讯", "AI 资讯站", true},
		{"/AI|人工智能/ 资讯", "AI 新闻站", false},
		{"/^glm/ 最新", "glm-4", false},
		{"/^glm/ 最新", "最新 glm-4", false},
		{"latest /^glm/", "glm-4 latest", true},
		// 空表达式匹配一切（保存时由 Validate 拒绝）
		{"", "anything", true},
	}
	for _, tt := range tests {
		expr, err := Compile(tt.expr)
		if err != nil {
			t.Fatalf("Compile(%q) error: %v", tt.expr, err)
		}
		if got := expr.Match(tt.text); got != tt.want {
			t.Errorf("Match(%q, %q) = %v, want %v", tt.expr, tt.text, got, tt.want)
		}
	}
}

func TestCompileFlagsAndEscapes(t *testing.T) {
	// 未写 flags 时默认忽略大小写（与普通匹配一致）
	insensitive, err := Compile("/AI/")
	if err != nil {
		t.Fatal(err)
	}
	if !insensitive.Match("AI") || !insensitive.Match("ai") {
		t.Errorf("default regex should be case-insensitive; got AI=%v ai=%v", insensitive.Match("AI"), insensitive.Match("ai"))
	}

	// g 标记对单次求值无意义，忽略后仍可编译
	if _, err := Compile("/AI/gi"); err != nil {
		t.Fatalf("g flag should be ignored, got error: %v", err)
	}

	// 转义斜杠
	escaped, err := Compile(`/gpt\/4/`)
	if err != nil {
		t.Fatalf("escaped slash compile error: %v", err)
	}
	if !escaped.Match("openai/gpt/4o") {
		t.Error("escaped slash pattern should match openai/gpt/4o")
	}

	// 非法正则与不支持的 flags
	if _, err := Compile("/[a-/"); err == nil {
		t.Error("invalid regex should fail to compile")
	}
	if _, err := Compile("/abc/z"); err == nil {
		t.Error("unsupported flag should fail to compile")
	}
}

func TestValidateAndMatchText(t *testing.T) {
	if err := Validate("   "); err == nil {
		t.Error("blank expression should be rejected")
	}
	if err := Validate("glm"); err != nil {
		t.Errorf("valid expression rejected: %v", err)
	}
	text := MatchText("glm-4.6", "ch-alpha")
	if text != "glm-4.6 (ch-alpha)" {
		t.Errorf("MatchText = %q", text)
	}
	expr, _ := Compile("alpha")
	if !expr.Match(text) {
		t.Error("rule should match channel name too")
	}
}
