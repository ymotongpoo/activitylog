// Package rules implements privacy and categorization rules.
package rules

import (
	"fmt"
	"net/url"
	"path"
	"regexp"
	"strings"

	"github.com/ymotongpoo/activitylog/agent/internal/model"
)

// Match selects activities. Empty fields match anything; all set fields must
// match (AND). App, Domain and Project are case-insensitive globs; Title,
// URL and File are regular expressions.
type Match struct {
	App     string `yaml:"app"`
	Title   string `yaml:"title"`
	Domain  string `yaml:"domain"`
	URL     string `yaml:"url"`
	Project string `yaml:"project"`
	File    string `yaml:"file"`
}

type matcher struct {
	app, domain, project string
	title, url, file     *regexp.Regexp
}

func compile(m Match) (*matcher, error) {
	c := &matcher{
		app:     strings.ToLower(m.App),
		domain:  strings.ToLower(m.Domain),
		project: strings.ToLower(m.Project),
	}
	for _, g := range []string{c.app, c.domain, c.project} {
		if _, err := path.Match(g, ""); err != nil {
			return nil, fmt.Errorf("bad glob %q: %w", g, err)
		}
	}
	var err error
	for _, p := range []struct {
		src string
		dst **regexp.Regexp
	}{{m.Title, &c.title}, {m.URL, &c.url}, {m.File, &c.file}} {
		if p.src == "" {
			continue
		}
		if *p.dst, err = regexp.Compile(p.src); err != nil {
			return nil, fmt.Errorf("bad regexp %q: %w", p.src, err)
		}
	}
	return c, nil
}

func glob(pattern, s string) bool {
	s = strings.ToLower(s)
	if ok, _ := path.Match(pattern, s); ok {
		return true
	}
	// "*.example.com" also matches "example.com".
	if rest, found := strings.CutPrefix(pattern, "*."); found && s == rest {
		return true
	}
	return false
}

func (c *matcher) match(a *model.Activity) bool {
	if c.app != "" && !glob(c.app, a.AppName) && !glob(c.app, a.AppID) {
		return false
	}
	if c.title != nil {
		t := a.Title
		if a.Browser != nil && a.Browser.Title != "" {
			t = a.Browser.Title
		}
		if !c.title.MatchString(t) {
			return false
		}
	}
	if c.domain != "" && !glob(c.domain, a.Domain()) {
		return false
	}
	if c.url != nil && (a.Browser == nil || !c.url.MatchString(a.Browser.URL)) {
		return false
	}
	if c.project != "" && (a.Editor == nil || !glob(c.project, a.Editor.Project)) {
		return false
	}
	if c.file != nil && (a.Editor == nil || !c.file.MatchString(a.Editor.File)) {
		return false
	}
	return true
}

// URL modes, from least to most restrictive.
const (
	URLFull   = "full"
	URLPath   = "path"
	URLDomain = "domain"
	URLNone   = "none"
)

var urlRank = map[string]int{"": 0, URLFull: 0, URLPath: 1, URLDomain: 2, URLNone: 3}

// PrivacyRule is one entry of privacy.rules.
type PrivacyRule struct {
	Match     Match  `yaml:"match"`
	Drop      bool   `yaml:"drop"`
	MaskTitle bool   `yaml:"mask_title"`
	URL       string `yaml:"url"`
}

// CategoryRule is one entry of categories. The first matching rule wins.
type CategoryRule struct {
	Name  string `yaml:"name"`
	Match Match  `yaml:"match"`
}

// Masked replaces masked titles.
const Masked = "[redacted]"

// Uncategorized is the category of activities no rule matched.
const Uncategorized = "Uncategorized"

// Set is a compiled set of rules.
type Set struct {
	privacy    []compiledPrivacy
	categories []compiledCategory
}

type compiledPrivacy struct {
	m *matcher
	r PrivacyRule
}

type compiledCategory struct {
	m    *matcher
	name string
}

// Compile validates and compiles the rules.
func Compile(privacy []PrivacyRule, categories []CategoryRule) (*Set, error) {
	s := &Set{}
	for i, r := range privacy {
		if _, ok := urlRank[r.URL]; !ok {
			return nil, fmt.Errorf("privacy.rules[%d]: unknown url mode %q", i, r.URL)
		}
		m, err := compile(r.Match)
		if err != nil {
			return nil, fmt.Errorf("privacy.rules[%d]: %w", i, err)
		}
		s.privacy = append(s.privacy, compiledPrivacy{m, r})
	}
	for i, r := range categories {
		if r.Name == "" {
			return nil, fmt.Errorf("categories[%d]: name is required", i)
		}
		m, err := compile(r.Match)
		if err != nil {
			return nil, fmt.Errorf("categories[%d]: %w", i, err)
		}
		s.categories = append(s.categories, compiledCategory{m, r.Name})
	}
	return s, nil
}

// Apply categorizes a using the raw data, then applies the privacy rules in
// place. All matching privacy rules are combined; for URLs the most
// restrictive mode wins.
func (s *Set) Apply(a *model.Activity) {
	a.Category = Uncategorized
	for _, c := range s.categories {
		if c.m.match(a) {
			a.Category = c.name
			break
		}
	}

	var drop, mask bool
	mode := URLFull
	for _, p := range s.privacy {
		if !p.m.match(a) {
			continue
		}
		drop = drop || p.r.Drop
		mask = mask || p.r.MaskTitle
		if urlRank[p.r.URL] > urlRank[mode] {
			mode = p.r.URL
		}
	}
	if drop {
		*a = model.Activity{Dropped: true, Category: a.Category}
		return
	}
	if mask {
		a.Title = Masked
		if a.Browser != nil {
			a.Browser.Title = Masked
		}
	}
	if a.Browser != nil {
		a.Browser.URL = ReduceURL(a.Browser.URL, mode)
	}
}

// ReduceURL strips parts of raw according to mode.
func ReduceURL(raw, mode string) string {
	if mode == URLFull || mode == "" {
		return raw
	}
	if mode == URLNone {
		return ""
	}
	u, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	u.RawQuery, u.Fragment, u.RawFragment, u.User = "", "", "", nil
	u.ForceQuery = false
	if mode == URLDomain {
		u.Path, u.RawPath = "", ""
	}
	return u.String()
}

// TerminalCommand reduces a shell command line according to mode
// (full, name or none).
func TerminalCommand(cmd, mode string) string {
	switch mode {
	case "full":
		return cmd
	case "none":
		return ""
	default:
		return CommandName(cmd)
	}
}

// CommandName returns the first word of cmd that is not an environment
// assignment, without its directory.
func CommandName(cmd string) string {
	for _, f := range strings.Fields(cmd) {
		if strings.Contains(f, "=") && !strings.HasPrefix(f, "=") {
			continue
		}
		switch f {
		case "sudo", "time", "nohup", "exec", "command", "builtin", "noglob", "nice":
			continue
		}
		return path.Base(f)
	}
	return ""
}
