package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const (
	sessionLifetime   = 12 * time.Hour
	freeAttempts      = 5
	baseLockout       = 30 * time.Second
	maxLockout        = 15 * time.Minute
	sessionTokenBytes = 32
)

var ErrLocked = errors.New("too many failed attempts")

func digest(value []byte) []byte {
	sum := sha256.Sum256(value)
	return sum[:]
}

type Auth struct {
	path        string
	now         func() time.Time
	mu          sync.Mutex
	sessions    map[string]time.Time
	failures    int
	lockedUntil time.Time
}

func NewAuth(root string, now func() time.Time) (*Auth, error) {
	auth := &Auth{path: filepath.Join(root, "sessions.json"), now: now, sessions: map[string]time.Time{}}
	data, err := os.ReadFile(auth.path)
	if errors.Is(err, os.ErrNotExist) {
		return auth, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(data, &auth.sessions); err != nil {
		return nil, err
	}
	return auth, nil
}

func (a *Auth) persist() error {
	data, err := json.Marshal(a.sessions)
	if err != nil {
		return err
	}
	return writeFileAtomic(a.path, data)
}

func (a *Auth) RetryAfter() time.Duration {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.retryAfterLocked()
}

func (a *Auth) retryAfterLocked() time.Duration {
	remaining := a.lockedUntil.Sub(a.now())
	if remaining < 0 {
		return 0
	}
	return remaining
}

func (a *Auth) Login(account *Account, authKey []byte) (string, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.retryAfterLocked() > 0 {
		return "", ErrLocked
	}
	if subtle.ConstantTimeCompare(digest(authKey), account.AuthDigest) != 1 {
		a.failures++
		if a.failures >= freeAttempts {
			lockout := baseLockout << (a.failures - freeAttempts)
			if lockout > maxLockout || lockout <= 0 {
				lockout = maxLockout
			}
			a.lockedUntil = a.now().Add(lockout)
		}
		return "", errors.New("invalid credentials")
	}
	a.failures = 0
	a.lockedUntil = time.Time{}
	raw := make([]byte, sessionTokenBytes)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	token := base64.RawURLEncoding.EncodeToString(raw)
	a.pruneLocked()
	a.sessions[sessionKey(token)] = a.now().Add(sessionLifetime)
	if err := a.persist(); err != nil {
		return "", err
	}
	return token, nil
}

func sessionKey(token string) string {
	return base64.RawURLEncoding.EncodeToString(digest([]byte(token)))
}

func (a *Auth) pruneLocked() {
	now := a.now()
	for key, expiry := range a.sessions {
		if now.After(expiry) {
			delete(a.sessions, key)
		}
	}
}

func (a *Auth) Valid(token string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	expiry, ok := a.sessions[sessionKey(token)]
	return ok && a.now().Before(expiry)
}

func (a *Auth) Logout(token string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	delete(a.sessions, sessionKey(token))
	return a.persist()
}

func (a *Auth) RevokeAll() error {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.sessions = map[string]time.Time{}
	return a.persist()
}
