package main

import (
	"errors"
	"fmt"
	"net"
	"os"
	"os/signal"
	"syscall"

	"github.com/BrokkAi/brokk/go-runtime/internal/runtime"
)

func main() {
	cfg, err := runtime.LoadConfig(os.Args[1:])
	if err != nil {
		if errors.Is(err, runtime.ErrHelpRequested) {
			fmt.Print(runtime.ExecutorUsage())
			os.Exit(0)
		}
		fmt.Fprintf(os.Stderr, "brokk-go-executor: %v\n\n%s", err, runtime.ExecutorUsage())
		os.Exit(1)
	}

	executor, err := runtime.NewExecutor(cfg)
	if err != nil {
		fmt.Fprintf(os.Stderr, "brokk-go-executor: %v\n", err)
		os.Exit(1)
	}

	listener, err := net.Listen("tcp", cfg.ListenAddr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "brokk-go-executor: listen failed: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Executor listening on http://%s\n", listener.Addr().String())

	done := make(chan struct{})
	if cfg.ExitOnStdinEOF {
		go func() {
			defer close(done)
			_ = runtime.WaitForStdinEOF()
			_ = listener.Close()
		}()
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	go func() {
		select {
		case <-sigCh:
			_ = listener.Close()
		case <-done:
		}
	}()

	if err := executor.Serve(listener); err != nil && !errors.Is(err, net.ErrClosed) {
		fmt.Fprintf(os.Stderr, "brokk-go-executor: server failed: %v\n", err)
		os.Exit(1)
	}
}
