package httpserver

import (
	"encoding/json"
	"net/http"
)

type Server struct {
	mux       *http.ServeMux
	authToken string
}

func New(authToken string) *Server {
	return &Server{
		mux:       http.NewServeMux(),
		authToken: authToken,
	}
}

func (s *Server) Handler() http.Handler {
	return s.mux
}

func (s *Server) RegisterUnauthenticated(path string, handler http.HandlerFunc) {
	s.mux.HandleFunc(path, handler)
}

func (s *Server) RegisterAuthenticated(path string, handler http.HandlerFunc) {
	s.mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+s.authToken {
			WriteJSON(w, http.StatusUnauthorized, ErrorPayload{
				Code:    "UNAUTHORIZED",
				Message: "Unauthorized",
			})
			return
		}
		handler(w, r)
	})
}

func WriteJSON(w http.ResponseWriter, statusCode int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(statusCode)
	_ = json.NewEncoder(w).Encode(payload)
}

func ParseJSON(r *http.Request, target any) error {
	defer r.Body.Close()
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	return decoder.Decode(target)
}

type ErrorPayload struct {
	Code    string  `json:"code"`
	Message string  `json:"message"`
	Details *string `json:"details"`
}

func ValidationError(message string) ErrorPayload {
	return ErrorPayload{Code: "VALIDATION_ERROR", Message: message}
}

func JobNotFound(jobID string) ErrorPayload {
	return ErrorPayload{Code: "JOB_NOT_FOUND", Message: "Job not found: " + jobID}
}

func InternalError(message string, err error) ErrorPayload {
	details := err.Error()
	return ErrorPayload{Code: "INTERNAL_ERROR", Message: message, Details: &details}
}
