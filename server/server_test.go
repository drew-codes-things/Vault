package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

const testSetupToken = "setup-secret"

type harness struct {
	t      *testing.T
	server *httptest.Server
	clock  *time.Time
	token  string
}

func newHarness(t *testing.T) *harness {
	t.Helper()
	dir := t.TempDir()
	current := time.Date(2026, 1, 1, 12, 0, 0, 0, time.UTC)
	store, err := NewStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	store.now = func() time.Time { return current }
	auth, err := NewAuth(dir, func() time.Time { return current })
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(NewAPI(store, auth, testSetupToken))
	t.Cleanup(server.Close)
	return &harness{t: t, server: server, clock: &current}
}

func (h *harness) do(method, path string, body io.Reader, headers map[string]string) *http.Response {
	h.t.Helper()
	request, err := http.NewRequest(method, h.server.URL+path, body)
	if err != nil {
		h.t.Fatal(err)
	}
	for key, value := range headers {
		request.Header.Set(key, value)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		h.t.Fatal(err)
	}
	h.t.Cleanup(func() { response.Body.Close() })
	return response
}

func (h *harness) authed(method, path string, body io.Reader) *http.Response {
	return h.do(method, path, body, map[string]string{"Authorization": "Bearer " + h.token})
}

func jsonBody(t *testing.T, value any) io.Reader {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return bytes.NewReader(data)
}

func readJSON(t *testing.T, response *http.Response, target any) {
	t.Helper()
	if err := json.NewDecoder(response.Body).Decode(target); err != nil {
		t.Fatal(err)
	}
}

func authKeyBytes(fill byte) []byte { return bytes.Repeat([]byte{fill}, 32) }

func (h *harness) setupAccount() {
	h.t.Helper()
	body := createAccountRequest{
		KDF:             KDFParams{Salt: bytes.Repeat([]byte{7}, 16), Iterations: 3, MemoryKiB: 65536, Parallelism: 1},
		AuthKey:         authKeyBytes(1),
		WrappedVaultKey: []byte("wrapped"),
	}
	response := h.do("POST", "/v1/account", jsonBody(h.t, body), map[string]string{"X-Setup-Token": testSetupToken})
	if response.StatusCode != http.StatusCreated {
		h.t.Fatalf("setup status %d", response.StatusCode)
	}
}

func (h *harness) login(fill byte) *http.Response {
	return h.do("POST", "/v1/login", jsonBody(h.t, loginRequest{AuthKey: authKeyBytes(fill)}), nil)
}

func (h *harness) signIn() {
	h.t.Helper()
	response := h.login(1)
	if response.StatusCode != http.StatusOK {
		h.t.Fatalf("login status %d", response.StatusCode)
	}
	var parsed loginResponse
	readJSON(h.t, response, &parsed)
	h.token = parsed.Token
	if string(parsed.WrappedVaultKey) != "wrapped" {
		h.t.Fatal("wrapped vault key not returned")
	}
}

func TestAccountSetupRequiresToken(t *testing.T) {
	h := newHarness(t)
	response := h.do("POST", "/v1/account", jsonBody(t, createAccountRequest{}), nil)
	if response.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403, got %d", response.StatusCode)
	}
	response = h.do("POST", "/v1/account", jsonBody(t, createAccountRequest{}), map[string]string{"X-Setup-Token": "wrong"})
	if response.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403, got %d", response.StatusCode)
	}
}

func TestAccountCanOnlyBeCreatedOnce(t *testing.T) {
	h := newHarness(t)
	h.setupAccount()
	body := createAccountRequest{KDF: KDFParams{Salt: bytes.Repeat([]byte{9}, 16)}, AuthKey: authKeyBytes(2), WrappedVaultKey: []byte("x")}
	response := h.do("POST", "/v1/account", jsonBody(t, body), map[string]string{"X-Setup-Token": testSetupToken})
	if response.StatusCode != http.StatusConflict {
		t.Fatalf("expected 409, got %d", response.StatusCode)
	}
}

func TestProtectedRoutesRejectMissingAndBadTokens(t *testing.T) {
	h := newHarness(t)
	h.setupAccount()
	for _, headers := range []map[string]string{nil, {"Authorization": "Bearer nope"}, {"Authorization": "Basic abc"}} {
		response := h.do("GET", "/v1/files", nil, headers)
		if response.StatusCode != http.StatusUnauthorized {
			t.Fatalf("expected 401, got %d", response.StatusCode)
		}
	}
}

func TestLoginLockoutAndRecovery(t *testing.T) {
	h := newHarness(t)
	h.setupAccount()
	for attempt := 0; attempt < freeAttempts; attempt++ {
		if status := h.login(9).StatusCode; status != http.StatusUnauthorized {
			t.Fatalf("attempt %d: expected 401, got %d", attempt, status)
		}
	}
	locked := h.login(1)
	if locked.StatusCode != http.StatusTooManyRequests || locked.Header.Get("Retry-After") == "" {
		t.Fatalf("expected 429 with Retry-After, got %d", locked.StatusCode)
	}
	*h.clock = h.clock.Add(baseLockout + time.Second)
	if status := h.login(1).StatusCode; status != http.StatusOK {
		t.Fatalf("expected login after lockout, got %d", status)
	}
}

func TestLockoutGrowsAndIsCapped(t *testing.T) {
	h := newHarness(t)
	h.setupAccount()
	for attempt := 0; attempt < 60; attempt++ {
		h.login(9)
		*h.clock = h.clock.Add(maxLockout + time.Second)
	}
	response := h.login(9)
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 after waiting out lockout, got %d", response.StatusCode)
	}
	response = h.login(9)
	retry := response.Header.Get("Retry-After")
	if response.StatusCode != http.StatusTooManyRequests || retry == "" {
		t.Fatalf("expected 429, got %d", response.StatusCode)
	}
}

func TestFullFileLifecycle(t *testing.T) {
	h := newHarness(t)
	h.setupAccount()
	h.signIn()

	var created map[string]string
	response := h.authed("POST", "/v1/files", jsonBody(t, createFileRequest{Meta: []byte("encrypted-meta")}))
	readJSON(t, response, &created)
	id := created["id"]
	if response.StatusCode != http.StatusCreated || len(id) != 32 {
		t.Fatalf("create failed: %d %q", response.StatusCode, id)
	}

	response = h.authed("GET", "/v1/files", nil)
	var listed []FileInfo
	readJSON(t, response, &listed)
	if len(listed) != 0 {
		t.Fatal("incomplete upload must not be listed")
	}

	head := h.authed("HEAD", "/v1/files/"+id+"/content", nil)
	if head.StatusCode != http.StatusOK || head.Header.Get("Content-Length") != "0" {
		t.Fatalf("new file should report size 0, got %d %q", head.StatusCode, head.Header.Get("Content-Length"))
	}

	first := []byte("0123456789")
	second := []byte("abcdefghij")
	response = h.authed("PUT", "/v1/files/"+id+"/content?offset=0", bytes.NewReader(first))
	if response.StatusCode != http.StatusOK {
		t.Fatalf("append status %d", response.StatusCode)
	}

	response = h.authed("PUT", "/v1/files/"+id+"/content?offset=3", bytes.NewReader(second))
	var mismatch struct{ Size int64 }
	readJSON(t, response, &mismatch)
	if response.StatusCode != http.StatusConflict || mismatch.Size != 10 {
		t.Fatalf("expected 409 with size 10, got %d size %d", response.StatusCode, mismatch.Size)
	}

	head = h.authed("HEAD", "/v1/files/"+id+"/content", nil)
	if head.Header.Get("Content-Length") != "10" {
		t.Fatalf("resume HEAD length %q", head.Header.Get("Content-Length"))
	}

	response = h.authed("PUT", "/v1/files/"+id+"/content?offset=10", bytes.NewReader(second))
	if response.StatusCode != http.StatusOK {
		t.Fatalf("second append status %d", response.StatusCode)
	}

	response = h.authed("POST", "/v1/files/"+id+"/finalize", jsonBody(t, finalizeRequest{Size: 99}))
	if response.StatusCode != http.StatusConflict {
		t.Fatalf("wrong-size finalize expected 409, got %d", response.StatusCode)
	}
	response = h.authed("POST", "/v1/files/"+id+"/finalize", jsonBody(t, finalizeRequest{Size: 20}))
	if response.StatusCode != http.StatusNoContent {
		t.Fatalf("finalize status %d", response.StatusCode)
	}

	response = h.authed("PUT", "/v1/files/"+id+"/content?offset=20", bytes.NewReader(first))
	if response.StatusCode != http.StatusConflict {
		t.Fatalf("append after finalize expected 409, got %d", response.StatusCode)
	}

	response = h.authed("PUT", "/v1/files/"+id+"/thumb", strings.NewReader("thumb-bytes"))
	if response.StatusCode != http.StatusNoContent {
		t.Fatalf("thumb status %d", response.StatusCode)
	}

	response = h.authed("GET", "/v1/files", nil)
	readJSON(t, response, &listed)
	if len(listed) != 1 || listed[0].Size != 20 || !listed[0].HasThumb || string(listed[0].Meta) != "encrypted-meta" {
		t.Fatalf("unexpected listing: %+v", listed)
	}

	request, _ := http.NewRequest("GET", h.server.URL+"/v1/files/"+id+"/content", nil)
	request.Header.Set("Authorization", "Bearer "+h.token)
	request.Header.Set("Range", "bytes=8-12")
	ranged, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer ranged.Body.Close()
	data, _ := io.ReadAll(ranged.Body)
	if ranged.StatusCode != http.StatusPartialContent || string(data) != "89abc" {
		t.Fatalf("range result %d %q", ranged.StatusCode, data)
	}

	response = h.authed("GET", "/v1/files/"+id+"/thumb", nil)
	data, _ = io.ReadAll(response.Body)
	if string(data) != "thumb-bytes" {
		t.Fatalf("thumb content %q", data)
	}

	response = h.authed("DELETE", "/v1/files/"+id, nil)
	if response.StatusCode != http.StatusNoContent {
		t.Fatalf("delete status %d", response.StatusCode)
	}
	response = h.authed("GET", "/v1/files/"+id+"/content", nil)
	if response.StatusCode != http.StatusNotFound {
		t.Fatalf("deleted file expected 404, got %d", response.StatusCode)
	}
	response = h.authed("GET", "/v1/files", nil)
	readJSON(t, response, &listed)
	if len(listed) != 0 {
		t.Fatal("deleted file still listed")
	}
}

func TestMalformedIDsCannotTraverse(t *testing.T) {
	h := newHarness(t)
	h.setupAccount()
	h.signIn()
	for _, id := range []string{"..", "%2e%2e", "..%2f..%2faccount.json", "short", strings.Repeat("g", 32)} {
		response := h.authed("GET", "/v1/files/"+id+"/content", nil)
		if response.StatusCode != http.StatusNotFound && response.StatusCode != http.StatusMovedPermanently && response.StatusCode != http.StatusBadRequest {
			t.Fatalf("id %q: unexpected status %d", id, response.StatusCode)
		}
		response = h.authed("DELETE", "/v1/files/"+id, nil)
		if response.StatusCode == http.StatusNoContent {
			t.Fatalf("id %q: delete must not succeed", id)
		}
	}
}

func TestOversizedMetaAndUnknownFieldsRejected(t *testing.T) {
	h := newHarness(t)
	h.setupAccount()
	h.signIn()
	response := h.authed("POST", "/v1/files", jsonBody(t, createFileRequest{Meta: make([]byte, maxMetaBytes+1)}))
	if response.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("expected 413, got %d", response.StatusCode)
	}
	response = h.authed("POST", "/v1/files", strings.NewReader(`{"meta":"YQ==","extra":1}`))
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", response.StatusCode)
	}
}

func TestLogoutInvalidatesToken(t *testing.T) {
	h := newHarness(t)
	h.setupAccount()
	h.signIn()
	if status := h.authed("POST", "/v1/logout", nil).StatusCode; status != http.StatusNoContent {
		t.Fatalf("logout status %d", status)
	}
	if status := h.authed("GET", "/v1/files", nil).StatusCode; status != http.StatusUnauthorized {
		t.Fatalf("expected 401 after logout, got %d", status)
	}
}

func TestSessionsExpire(t *testing.T) {
	h := newHarness(t)
	h.setupAccount()
	h.signIn()
	*h.clock = h.clock.Add(sessionLifetime + time.Hour)
	if status := h.authed("GET", "/v1/files", nil).StatusCode; status != http.StatusUnauthorized {
		t.Fatalf("expected 401 after expiry, got %d", status)
	}
}

func TestStaleIncompleteUploadsAreRemoved(t *testing.T) {
	h := newHarness(t)
	h.setupAccount()
	h.signIn()
	var created map[string]string
	readJSON(t, h.authed("POST", "/v1/files", jsonBody(t, createFileRequest{Meta: []byte("m")})), &created)
	h.authed("PUT", "/v1/files/"+created["id"]+"/content?offset=0", strings.NewReader("partial"))

	h.authed("GET", "/v1/files", nil)
	if status := h.authed("HEAD", "/v1/files/"+created["id"]+"/content", nil).StatusCode; status != http.StatusOK {
		t.Fatalf("fresh incomplete upload must survive listing, got %d", status)
	}

	*h.clock = h.clock.Add(staleUploadAge + time.Hour)
	h.signIn()
	h.authed("GET", "/v1/files", nil)
	if status := h.authed("HEAD", "/v1/files/"+created["id"]+"/content", nil).StatusCode; status != http.StatusNotFound {
		t.Fatalf("stale incomplete upload should be removed, got %d", status)
	}
}

func TestMetadataCanBeReplaced(t *testing.T) {
	h := newHarness(t)
	h.setupAccount()
	h.signIn()
	var created map[string]string
	readJSON(t, h.authed("POST", "/v1/files", jsonBody(t, createFileRequest{Meta: []byte("old")})), &created)
	id := created["id"]
	h.authed("PUT", "/v1/files/"+id+"/content?offset=0", strings.NewReader("x"))
	h.authed("POST", "/v1/files/"+id+"/finalize", jsonBody(t, finalizeRequest{Size: 1}))

	if status := h.authed("PUT", "/v1/files/"+id+"/meta", jsonBody(t, createFileRequest{Meta: []byte("new")})).StatusCode; status != http.StatusNoContent {
		t.Fatalf("meta update status %d", status)
	}
	var listed []FileInfo
	readJSON(t, h.authed("GET", "/v1/files", nil), &listed)
	if len(listed) != 1 || string(listed[0].Meta) != "new" {
		t.Fatalf("meta not replaced: %+v", listed)
	}
	if status := h.authed("PUT", "/v1/files/"+id+"/meta", jsonBody(t, createFileRequest{Meta: make([]byte, maxMetaBytes+1)})).StatusCode; status != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversized meta expected 413, got %d", status)
	}
	if status := h.authed("PUT", "/v1/files/"+strings.Repeat("0", 32)+"/meta", jsonBody(t, createFileRequest{Meta: []byte("x")})).StatusCode; status != http.StatusNotFound {
		t.Fatalf("unknown file expected 404, got %d", status)
	}
	if status := h.do("PUT", "/v1/files/"+id+"/meta", jsonBody(t, createFileRequest{Meta: []byte("x")}), nil).StatusCode; status != http.StatusUnauthorized {
		t.Fatalf("unauthenticated meta update expected 401, got %d", status)
	}
}
