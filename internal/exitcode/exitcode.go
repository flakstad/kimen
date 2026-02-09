package exitcode

import "fmt"

// Error carries an intended process exit code.
// It is useful for subcommands that should forward child process exit statuses.
type Error struct {
	Code int
	Err  error
}

func (e *Error) Error() string {
	if e.Err == nil {
		return fmt.Sprintf("exit code %d", e.Code)
	}
	return e.Err.Error()
}

func (e *Error) Unwrap() error { return e.Err }

func New(code int, err error) *Error {
	return &Error{Code: code, Err: err}
}
