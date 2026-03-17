package main

import (
	"fmt"
	"os"

	"github.com/BrokkAi/brokk/go-runtime/internal/runtime"
)

func main() {
	if len(os.Args) > 1 {
		for _, arg := range os.Args[1:] {
			if arg == "--help" || arg == "-h" {
				fmt.Print(runtime.MCPUsage())
				return
			}
		}
	}

	server, err := runtime.NewMCPServer(".")
	if err != nil {
		fmt.Fprintf(os.Stderr, "brokk-go-mcp: %v\n", err)
		os.Exit(1)
	}
	if err := server.Run(os.Stdin, os.Stdout); err != nil {
		fmt.Fprintf(os.Stderr, "brokk-go-mcp: %v\n", err)
		os.Exit(1)
	}
}
