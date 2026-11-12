package main

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const maxJSONBytes = 1 << 20

type API struct {
	store          *Store
	auth           *Auth
	setupToken     string
	clientIPHeader string
}

func NewAPI(store *Store, auth *Auth, setupToken string, clientIPHeader string) http.Handler {
	api := &API{store: store, auth: auth, setupToken: setupToken, clientIPHeader: clientIPHeader}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/kdf", api.kdf)
	mux.HandleFunc("POST /v1/account", api.createAccount)
	mux.HandleFunc("POST /v1/login", api.login)
	mux.HandleFunc("POST /v1/logout", api.protect(api.logout))
	mux.HandleFunc("GET /v1/files", api.protect(api.listFiles))
	mux.HandleFunc("POST /v1/files", api.protect(api.createFile))
	mux.HandleFunc("PUT /v1/files/{id}/content", api.protect(api.appendContent))
	mux.HandleFunc("GET /v1/files/{id}/content", api.protect(api.serveBlob("content.bin")))
	mux.HandleFunc("PUT /v1/files/{id}/thumb", api.protect(api.putThumb))
	mux.HandleFunc("GET /v1/files/{id}/thumb", api.protect(api.serveBlob("thumb.bin")))
	mux.HandleFunc("POST /v1/files/{id}/finalize", api.protect(api.finalize))
	mux.HandleFunc("PUT /v1/files/{id}/meta", api.protect(api.updateMeta))
	mux.HandleFunc("DELETE /v1/files/{id}", api.protect(api.deleteFile))
	return withSecurityHeaders(mux)
}

func withSecurityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Content-Security-Policy", "default-src 'none'")
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(value); err != nil {
		log.Printf("encode response: %v", err)
	}
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func decodeJSON(w http.ResponseWriter, r *http.Request, target any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxJSONBytes)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return false
	}
	return true
}

func bearerToken(r *http.Request) string {
	header := r.Header.Get("Authorization")
	token, found := strings.CutPrefix(header, "Bearer ")
	if !found {
		return ""
	}
	return token
}

func (a *API) protect(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token := bearerToken(r)
		if token == "" || !a.auth.Valid(token) {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		next(w, r)
	}
}

func (a *API) storeError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, ErrNotFound):
		writeError(w, http.StatusNotFound, "not found")
	case errors.Is(err, ErrTooLarge):
		writeError(w, http.StatusRequestEntityTooLarge, "payload too large")
	case errors.Is(err, ErrIncomplete):
		writeError(w, http.StatusConflict, "upload size does not match")
	default:
		log.Printf("store error: %v", err)
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}

func (a *API) kdf(w http.ResponseWriter, r *http.Request) {
	account, err := a.store.LoadAccount()
	if err != nil {
		a.storeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, account.KDF)
}

type createAccountRequest struct {
	KDF             KDFParams `json:"kdf"`
	AuthKey         []byte    `json:"authKey"`
	WrappedVaultKey []byte    `json:"wrappedVaultKey"`
}

func (a *API) createAccount(w http.ResponseWriter, r *http.Request) {
	provided := r.Header.Get("X-Setup-Token")
	if a.setupToken == "" || subtle.ConstantTimeCompare([]byte(provided), []byte(a.setupToken)) != 1 {
		writeError(w, http.StatusForbidden, "forbidden")
		return
	}
	var request createAccountRequest
	if !decodeJSON(w, r, &request) {
		return
	}
	if len(request.AuthKey) != 32 || len(request.KDF.Salt) < 16 || len(request.WrappedVaultKey) == 0 {
		writeError(w, http.StatusBadRequest, "invalid account parameters")
		return
	}
	account := Account{KDF: request.KDF, AuthDigest: digest(request.AuthKey), WrappedVaultKey: request.WrappedVaultKey}
	if err := a.store.CreateAccount(account); err != nil {
		if errors.Is(err, ErrAccountExists) {
			writeError(w, http.StatusConflict, "account already exists")
			return
		}
		a.storeError(w, err)
		return
	}
	w.WriteHeader(http.StatusCreated)
}

type loginRequest struct {
	AuthKey []byte `json:"authKey"`
}

type loginResponse struct {
	Token           string `json:"token"`
	WrappedVaultKey []byte `json:"wrappedVaultKey"`
}

func (a *API) clientSource(r *http.Request) string {
	if a.clientIPHeader != "" {
		if forwarded := net.ParseIP(strings.TrimSpace(r.Header.Get(a.clientIPHeader))); forwarded != nil {
			return forwarded.String()
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func (a *API) login(w http.ResponseWriter, r *http.Request) {
	var request loginRequest
	if !decodeJSON(w, r, &request) {
		return
	}
	account, err := a.store.LoadAccount()
	if err != nil {
		a.storeError(w, err)
		return
	}
	source := a.clientSource(r)
	token, err := a.auth.Login(account, request.AuthKey, source)
	if errors.Is(err, ErrLocked) {
		retry := int(a.auth.RetryAfter(source)/time.Second) + 1
		w.Header().Set("Retry-After", strconv.Itoa(retry))
		writeError(w, http.StatusTooManyRequests, "too many failed attempts")
		return
	}
	if err != nil {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}
	writeJSON(w, http.StatusOK, loginResponse{Token: token, WrappedVaultKey: account.WrappedVaultKey})
}

func (a *API) logout(w http.ResponseWriter, r *http.Request) {
	if err := a.auth.Logout(bearerToken(r)); err != nil {
		a.storeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *API) listFiles(w http.ResponseWriter, r *http.Request) {
	files, err := a.store.List()
	if err != nil {
		a.storeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, files)
}

type createFileRequest struct {
	Meta []byte `json:"meta"`
}

func (a *API) createFile(w http.ResponseWriter, r *http.Request) {
	var request createFileRequest
	if !decodeJSON(w, r, &request) {
		return
	}
	id, err := a.store.CreateFile(request.Meta)
	if err != nil {
		a.storeError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{"id": id})
}

func (a *API) appendContent(w http.ResponseWriter, r *http.Request) {
	offset, err := strconv.ParseInt(r.URL.Query().Get("offset"), 10, 64)
	if err != nil || offset < 0 {
		writeError(w, http.StatusBadRequest, "invalid offset")
		return
	}
	stored, err := a.store.AppendContent(r.PathValue("id"), offset, r.Body)
	if errors.Is(err, ErrOffsetMismatch) {
		writeJSON(w, http.StatusConflict, map[string]any{"error": "offset mismatch", "size": stored})
		return
	}
	if err != nil {
		a.storeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]int64{"size": stored})
}

func (a *API) putThumb(w http.ResponseWriter, r *http.Request) {
	if err := a.store.PutThumb(r.PathValue("id"), r.Body); err != nil {
		a.storeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *API) updateMeta(w http.ResponseWriter, r *http.Request) {
	var request createFileRequest
	if !decodeJSON(w, r, &request) {
		return
	}
	if err := a.store.UpdateMeta(r.PathValue("id"), request.Meta); err != nil {
		a.storeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

type finalizeRequest struct {
	Size int64 `json:"size"`
}

func (a *API) finalize(w http.ResponseWriter, r *http.Request) {
	var request finalizeRequest
	if !decodeJSON(w, r, &request) {
		return
	}
	if err := a.store.Finalize(r.PathValue("id"), request.Size); err != nil {
		a.storeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *API) serveBlob(name string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		file, err := a.store.OpenBlob(r.PathValue("id"), name)
		if err != nil {
			a.storeError(w, err)
			return
		}
		defer file.Close()
		w.Header().Set("Content-Type", "application/octet-stream")
		http.ServeContent(w, r, "", time.Time{}, file)
	}
}

func (a *API) deleteFile(w http.ResponseWriter, r *http.Request) {
	if err := a.store.Delete(r.PathValue("id")); err != nil {
		a.storeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
