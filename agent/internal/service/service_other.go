//go:build !darwin && !linux

package service

import "errors"

var errUnsupported = errors.New("services are not supported on this operating system")

func LogPath() string  { return "" }
func Install() error   { return errUnsupported }
func Uninstall() error { return errUnsupported }
func Restart() error   { return errUnsupported }
func Status() error    { return errUnsupported }
