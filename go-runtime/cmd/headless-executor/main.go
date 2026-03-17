package main

import (
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/brokk/brokk/go-runtime/internal/app"
	"github.com/brokk/brokk/go-runtime/internal/config"
)

func main() {
	cfg, err := config.Load(os.Args[1:], os.Getenv)
	if err != nil {
		var usageErr *config.UsageError
		if errors.As(err, &usageErr) {
			fmt.Fprint(os.Stderr, usageErr.Message)
			os.Exit(usageErr.ExitCode)
		}

		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	runtimeApp, err := app.New(cfg)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	errCh := make(chan error, 1)
	go func() {
		errCh <- runtimeApp.Start()
	}()

	if cfg.ExitOnStdinEOF {
		go func() {
			var buf [1]byte
			_, _ = os.Stdin.Read(buf[:])
			_ = runtimeApp.Shutdown()
		}()
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	select {
	case sig := <-sigCh:
		_ = sig
		_ = runtimeApp.Shutdown()
	case err := <-errCh:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
	}
}
