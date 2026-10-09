//go:build darwin

#import <Cocoa/Cocoa.h>
#import <ApplicationServices/ApplicationServices.h>
#import <IOKit/pwr_mgt/IOPMLib.h>
#include <stdlib.h>
#include <string.h>
#include "darwin.h"

// This file is compiled without ARC.

static char *copy_string(NSString *s) {
	if (s == nil) {
		return NULL;
	}
	return strdup([s UTF8String]);
}

void al_run_main_loop(void) {
	@autoreleasepool {
		// CFRunLoopRun returns immediately when the run loop has no sources,
		// so keep a timer that never fires in practice.
		CFRunLoopTimerRef timer = CFRunLoopTimerCreateWithHandler(
			NULL, CFAbsoluteTimeGetCurrent() + 1e10, 1e10, 0, 0, ^(CFRunLoopTimerRef t) {});
		CFRunLoopAddTimer(CFRunLoopGetMain(), timer, kCFRunLoopCommonModes);
		CFRunLoopRun();
		CFRunLoopRemoveTimer(CFRunLoopGetMain(), timer, kCFRunLoopCommonModes);
		CFRelease(timer);
	}
}

void al_stop_main_loop(void) {
	CFRunLoopStop(CFRunLoopGetMain());
}

static char *ax_window_title(pid_t pid) {
	char *result = NULL;
	AXUIElementRef app = AXUIElementCreateApplication(pid);
	if (app == NULL) {
		return NULL;
	}
	// Do not let an unresponsive application stall the poll loop.
	AXUIElementSetMessagingTimeout(app, 0.5);
	CFTypeRef win = NULL;
	if (AXUIElementCopyAttributeValue(app, kAXFocusedWindowAttribute, &win) == kAXErrorSuccess && win != NULL) {
		CFTypeRef title = NULL;
		if (AXUIElementCopyAttributeValue((AXUIElementRef)win, kAXTitleAttribute, &title) == kAXErrorSuccess &&
			title != NULL) {
			if (CFGetTypeID(title) == CFStringGetTypeID()) {
				result = copy_string((NSString *)title);
			}
			CFRelease(title);
		}
		CFRelease(win);
	}
	CFRelease(app);
	return result;
}

static pid_t ax_focused_pid(void) {
	pid_t pid = 0;
	AXUIElementRef sys = AXUIElementCreateSystemWide();
	if (sys == NULL) {
		return 0;
	}
	AXUIElementSetMessagingTimeout(sys, 0.5);
	CFTypeRef app = NULL;
	if (AXUIElementCopyAttributeValue(sys, kAXFocusedApplicationAttribute, &app) == kAXErrorSuccess && app != NULL) {
		AXUIElementGetPid((AXUIElementRef)app, &pid);
		CFRelease(app);
	}
	CFRelease(sys);
	return pid;
}

int al_frontmost(al_window *w) {
	@autoreleasepool {
		memset(w, 0, sizeof(*w));
		BOOL trusted = AXIsProcessTrusted();
		NSRunningApplication *app = nil;
		if (trusted) {
			pid_t pid = ax_focused_pid();
			if (pid > 0) {
				app = [NSRunningApplication runningApplicationWithProcessIdentifier:pid];
			}
		}
		if (app == nil) {
			app = [[NSWorkspace sharedWorkspace] frontmostApplication];
		}
		if (app == nil) {
			return 0;
		}
		w->pid = app.processIdentifier;
		w->app_name = copy_string(app.localizedName);
		w->bundle_id = copy_string(app.bundleIdentifier);
		if (trusted) {
			w->title = ax_window_title(app.processIdentifier);
		}
		return 1;
	}
}

double al_idle_seconds(void) {
	return CGEventSourceSecondsSinceLastEventType(kCGEventSourceStateHIDSystemState, kCGAnyInputEventType);
}

static int cf_truthy(CFTypeRef v) {
	if (v == NULL) {
		return 0;
	}
	if (CFGetTypeID(v) == CFBooleanGetTypeID()) {
		return CFBooleanGetValue((CFBooleanRef)v);
	}
	if (CFGetTypeID(v) == CFNumberGetTypeID()) {
		int n = 0;
		CFNumberGetValue((CFNumberRef)v, kCFNumberIntType, &n);
		return n != 0;
	}
	return 0;
}

int al_screen_locked(void) {
	CFDictionaryRef d = CGSessionCopyCurrentDictionary();
	if (d == NULL) {
		// No window server session, e.g. at the login window.
		return 1;
	}
	int locked = cf_truthy(CFDictionaryGetValue(d, CFSTR("CGSSessionScreenIsLocked")));
	CFTypeRef console = CFDictionaryGetValue(d, kCGSessionOnConsoleKey);
	if (console != NULL && !cf_truthy(console)) {
		// Another user is on the console (fast user switching).
		locked = 1;
	}
	CFRelease(d);
	return locked;
}

int al_idle_inhibited(void) {
	CFDictionaryRef d = NULL;
	if (IOPMCopyAssertionsStatus(&d) != kIOReturnSuccess || d == NULL) {
		return 0;
	}
	int inhibited = cf_truthy(CFDictionaryGetValue(d, kIOPMAssertPreventUserIdleDisplaySleep));
	CFRelease(d);
	return inhibited;
}

int al_ax_trusted(int prompt) {
	@autoreleasepool {
		NSDictionary *opts = @{(id)kAXTrustedCheckOptionPrompt : @(prompt ? YES : NO)};
		return AXIsProcessTrustedWithOptions((CFDictionaryRef)opts) ? 1 : 0;
	}
}

int al_automation(const char *bundle_id, int ask) {
	AEAddressDesc target;
	OSErr err = AECreateDesc(typeApplicationBundleID, bundle_id, strlen(bundle_id), &target);
	if (err != noErr) {
		return err;
	}
	OSStatus st = AEDeterminePermissionToAutomateTarget(&target, typeWildCard, typeWildCard, ask ? true : false);
	AEDisposeDesc(&target);
	return (int)st;
}

char *al_running_apps(void) {
	@autoreleasepool {
		NSMutableArray *ids = [NSMutableArray array];
		for (NSRunningApplication *app in [[NSWorkspace sharedWorkspace] runningApplications]) {
			if (app.bundleIdentifier != nil) {
				[ids addObject:app.bundleIdentifier];
			}
		}
		return copy_string([ids componentsJoinedByString:@"\n"]);
	}
}

static NSMutableDictionary *compiled_scripts;

int al_applescript(const char *src, char **out, char **err) {
	*out = NULL;
	*err = NULL;
	__block int code = 0;
	__block char *res = NULL;
	__block char *msg = NULL;
	@autoreleasepool {
		NSString *source = [NSString stringWithUTF8String:src];
		// NSAppleScript is not thread safe; run it on the main thread, whose
		// run loop is driven by al_run_main_loop.
		dispatch_sync(dispatch_get_main_queue(), ^{
			@autoreleasepool {
				if (compiled_scripts == nil) {
					compiled_scripts = [[NSMutableDictionary alloc] init];
				}
				NSDictionary *errInfo = nil;
				NSAppleScript *script = compiled_scripts[source];
				if (script == nil) {
					script = [[[NSAppleScript alloc] initWithSource:source] autorelease];
					if (![script compileAndReturnError:&errInfo]) {
						NSNumber *n = errInfo[NSAppleScriptErrorNumber];
						code = n != nil ? n.intValue : -1;
						msg = copy_string(errInfo[NSAppleScriptErrorMessage]);
						return;
					}
					compiled_scripts[source] = script;
				}
				NSAppleEventDescriptor *d = [script executeAndReturnError:&errInfo];
				if (d == nil) {
					NSNumber *n = errInfo[NSAppleScriptErrorNumber];
					code = n != nil ? n.intValue : -1;
					msg = copy_string(errInfo[NSAppleScriptErrorMessage]);
					return;
				}
				res = copy_string(d.stringValue);
			}
		});
	}
	*out = res;
	*err = msg;
	return code;
}
