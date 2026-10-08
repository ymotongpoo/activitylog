package rules

import (
	"testing"

	"github.com/ymotongpoo/activitylog/agent/internal/model"
)

func browser(app, url, title string) *model.Activity {
	return &model.Activity{
		AppName: app, AppID: "com.google.Chrome", Title: title, Kind: model.KindBrowser,
		Browser: &model.BrowserInfo{Name: "chrome", URL: url, Title: title},
	}
}

func TestPrivacy(t *testing.T) {
	s, err := Compile([]PrivacyRule{
		{Match: Match{App: "1password*"}, Drop: true},
		{Match: Match{Domain: "*.bank.example"}, Drop: true},
		{Match: Match{Title: "(?i)secret"}, MaskTitle: true},
		{Match: Match{}, URL: URLPath},
		{Match: Match{Domain: "mail.google.com"}, URL: URLDomain},
	}, nil)
	if err != nil {
		t.Fatal(err)
	}

	a := &model.Activity{AppName: "1Password 8", AppID: "com.1password.1password"}
	s.Apply(a)
	if !a.Dropped || a.AppName != "" {
		t.Errorf("1Password not dropped: %+v", a)
	}

	for _, u := range []string{"https://bank.example/login", "https://www.bank.example/"} {
		a = browser("Google Chrome", u, "Bank")
		s.Apply(a)
		if !a.Dropped {
			t.Errorf("%s not dropped", u)
		}
	}

	a = browser("Google Chrome", "https://example.com/a?q=1#frag", "My Secret Doc")
	s.Apply(a)
	if a.Title != Masked || a.Browser.Title != Masked {
		t.Errorf("title not masked: %q %q", a.Title, a.Browser.Title)
	}
	if a.Browser.URL != "https://example.com/a" {
		t.Errorf("url = %q", a.Browser.URL)
	}

	// The most restrictive URL mode wins regardless of rule order.
	a = browser("Google Chrome", "https://mail.google.com/mail/u/0/#inbox", "Inbox")
	s.Apply(a)
	if a.Browser.URL != "https://mail.google.com" {
		t.Errorf("url = %q", a.Browser.URL)
	}
}

func TestCategories(t *testing.T) {
	s, err := Compile([]PrivacyRule{{Match: Match{App: "Slack"}, MaskTitle: true}}, []CategoryRule{
		{Name: "Work/Programming", Match: Match{App: "*code*"}},
		{Name: "Communication", Match: Match{App: "Slack", Title: "general"}},
		{Name: "Media", Match: Match{Domain: "*.youtube.com"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		a    *model.Activity
		want string
	}{
		{&model.Activity{AppName: "Code", AppID: "com.microsoft.VSCode"}, "Work/Programming"},
		{&model.Activity{AppName: "Slack", Title: "#general"}, "Communication"},
		{browser("Chrome", "https://www.youtube.com/watch?v=1", "x"), "Media"},
		{browser("Chrome", "https://youtube.com/", "x"), "Media"},
		{&model.Activity{AppName: "Finder"}, Uncategorized},
	}
	for _, c := range cases {
		s.Apply(c.a)
		if c.a.Category != c.want {
			t.Errorf("%s: category = %q, want %q", c.a.AppName, c.a.Category, c.want)
		}
	}
}

func TestCompileErrors(t *testing.T) {
	if _, err := Compile([]PrivacyRule{{URL: "bogus"}}, nil); err == nil {
		t.Error("unknown url mode accepted")
	}
	if _, err := Compile([]PrivacyRule{{Match: Match{Title: "("}}}, nil); err == nil {
		t.Error("bad regexp accepted")
	}
	if _, err := Compile(nil, []CategoryRule{{Match: Match{App: "x"}}}); err == nil {
		t.Error("category without name accepted")
	}
}

func TestCommandName(t *testing.T) {
	for in, want := range map[string]string{
		"go test ./...":            "go",
		"FOO=1 BAR=2 make build":   "make",
		"sudo /usr/bin/apt update": "apt",
		"  ls -la":                 "ls",
		"":                         "",
	} {
		if got := CommandName(in); got != want {
			t.Errorf("CommandName(%q) = %q, want %q", in, got, want)
		}
	}
	if got := TerminalCommand("curl -H 'Authorization: x' u", "name"); got != "curl" {
		t.Errorf("name mode = %q", got)
	}
	if got := TerminalCommand("ls", "none"); got != "" {
		t.Errorf("none mode = %q", got)
	}
}
