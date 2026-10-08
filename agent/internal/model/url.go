package model

import (
	"net/url"
	"strings"
)

// URLDomain returns the lowercased host of rawURL without port.
func URLDomain(rawURL string) string {
	u, err := url.Parse(rawURL)
	if err != nil {
		return ""
	}
	return strings.ToLower(u.Hostname())
}

// URLParts splits rawURL into scheme, domain and path. It returns ok=false
// when rawURL cannot be parsed.
func URLParts(rawURL string) (scheme, domain, path string, ok bool) {
	u, err := url.Parse(rawURL)
	if err != nil || u.Scheme == "" {
		return "", "", "", false
	}
	return u.Scheme, strings.ToLower(u.Hostname()), u.EscapedPath(), true
}
