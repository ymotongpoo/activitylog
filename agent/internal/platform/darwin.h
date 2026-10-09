#ifndef ACTIVITYLOG_DARWIN_H
#define ACTIVITYLOG_DARWIN_H

typedef struct {
	int pid;
	char *app_name;
	char *bundle_id;
	char *title;
} al_window;

void al_run_main_loop(void);
void al_stop_main_loop(void);

// Fills w with the frontmost application. Returns 0 if there is none.
// Strings must be released with free().
int al_frontmost(al_window *w);

double al_idle_seconds(void);
int al_screen_locked(void);
int al_idle_inhibited(void);
int al_ax_trusted(int prompt);

// Returns the Automation permission of this process for the application
// bundle_id: 0 when granted, otherwise errAEEventNotPermitted (-1743),
// errAEEventWouldRequireUserConsent (-1744) or procNotFound (-600). With ask
// set, macOS shows the consent dialog if needed and the call blocks until
// the user answers; it must not be called on the main thread then.
int al_automation(const char *bundle_id, int ask);

// Returns the bundle identifiers of the running applications, separated by
// newlines. The string must be released with free().
char *al_running_apps(void);

// Runs an AppleScript on the main thread. Returns 0 on success, otherwise
// the AppleScript error number. *out and *err must be released with free().
int al_applescript(const char *src, char **out, char **err);

#endif
