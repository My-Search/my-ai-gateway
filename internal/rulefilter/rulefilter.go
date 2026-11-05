// Package rulefilter 实现「规则匹配」关联（动态模型集合）的表达式解析与求值。
//
// 表达式语法与前端过滤选择（frontend/src/utils/filterMatch.ts）保持一致：
//   - 普通匹配："glm 最新模型" — 空格分词，每个词都必须出现（不区分大小写）；
//   - 正则匹配："/AI|人工智能/" — 斜杠包裹正则表达式，未显式写 flags 时默认
//     忽略大小写（可写 "/AI/i"）；正则内的 "/" 需写成 "\/"；
//   - 混合匹配："/AI|人工智能/ 资讯" — 正则段与普通关键词并存，全部命中才算匹配。
//
// 规则在路由时动态求值：命中的渠道模型随渠道配置变化自动增减。
package rulefilter

import (
	"fmt"
	"regexp"
	"strings"
)

// Expr 是编译后的匹配表达式。
type Expr struct {
	keywords []string
	regexes  []*regexp.Regexp
}

// regexSegment 识别 /pattern/flags 形式的正则段；pattern 支持 \/ 转义。
var regexSegment = regexp.MustCompile(`/((?:\\.|[^/\\])+)/([a-z]*)`)

// Compile 把表达式编译为 Expr。空表达式返回匹配一切的空 Expr；
// 正则段编译失败返回 error，调用方应拒绝保存该规则。
func Compile(expr string) (*Expr, error) {
	out := &Expr{}
	source := expr
	last := 0
	for _, m := range regexSegment.FindAllStringSubmatchIndex(source, -1) {
		out.keywords = appendKeywords(out.keywords, source[last:m[0]])
		pattern := unescapeSlashes(source[m[2]:m[3]])
		flags := strings.ToLower(source[m[4]:m[5]])
		re, err := compileRegex(pattern, flags)
		if err != nil {
			return nil, err
		}
		out.regexes = append(out.regexes, re)
		last = m[1]
	}
	out.keywords = appendKeywords(out.keywords, source[last:])
	return out, nil
}

// appendKeywords 按空格切分并转小写，追加到 dst。
func appendKeywords(dst []string, text string) []string {
	for _, kw := range strings.Fields(strings.ToLower(text)) {
		if kw != "" {
			dst = append(dst, kw)
		}
	}
	return dst
}

// unescapeSlashes 把前端写法里的 \/ 还原为 /（Go 的 regexp 不接受 \/ 转义）。
func unescapeSlashes(pattern string) string {
	return strings.ReplaceAll(pattern, `\/`, `/`)
}

// compileRegex 按显式 flags 编译；未指定时默认忽略大小写。
// g / y 对单次求值无意义，直接忽略；u 由 Go 的 RE2 默认支持。
func compileRegex(pattern, flags string) (*regexp.Regexp, error) {
	var opts strings.Builder
	for _, f := range flags {
		switch f {
		case 'i':
			opts.WriteString("(?i)")
		case 'm':
			opts.WriteString("(?m)")
		case 's':
			opts.WriteString("(?s)")
		case 'g', 'y', 'u':
			// ignored
		default:
			return nil, fmt.Errorf("不支持的正则标记 %q", string(f))
		}
	}
	if opts.Len() == 0 {
		opts.WriteString("(?i)")
	}
	re, err := regexp.Compile(opts.String() + pattern)
	if err != nil {
		return nil, fmt.Errorf("无效正则 /%s/: %w", pattern, err)
	}
	return re, nil
}

// Match 判断 text 是否命中表达式。空表达式匹配一切。
func (e *Expr) Match(text string) bool {
	if e == nil {
		return false
	}
	lower := strings.ToLower(text)
	for _, kw := range e.keywords {
		if !strings.Contains(lower, kw) {
			return false
		}
	}
	for _, re := range e.regexes {
		if !re.MatchString(text) {
			return false
		}
	}
	return true
}

// MatchText 是渠道模型在「规则匹配」中的求值文本：与前端候选标签一致，
// 同时包含模型名与渠道名，因此规则既可以按模型名也能按渠道名筛选。
func MatchText(modelName, channelName string) string {
	return modelName + " (" + channelName + ")"
}

// Validate 校验表达式可编译且非空（空规则会匹配所有渠道模型，视为无效配置）。
func Validate(expr string) error {
	if strings.TrimSpace(expr) == "" {
		return fmt.Errorf("匹配规则不能为空")
	}
	_, err := Compile(expr)
	return err
}
