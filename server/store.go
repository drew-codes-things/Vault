package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"sync"
	"time"
)

const (
	maxMetaBytes   = 64 << 10
	maxThumbBytes  = 8 << 20
	maxAppendBytes = 256 << 20
	staleUploadAge = 24 * time.Hour
)

var (
	ErrNotFound       = errors.New("not found")
	ErrOffsetMismatch = errors.New("offset does not match stored size")
	ErrTooLarge       = errors.New("payload too large")
	ErrAccountExists  = errors.New("account already exists")
	ErrIncomplete     = errors.New("upload size does not match")
	ErrUnreadableFile = errors.New("stored file details cannot be read")
	idPattern         = regexp.MustCompile(`^[0-9a-f]{32}$`)
)

type KDFParams struct {
	Salt        []byte `json:"salt"`
	Iterations  uint32 `json:"iterations"`
	MemoryKiB   uint32 `json:"memoryKiB"`
	Parallelism uint32 `json:"parallelism"`
}

type Account struct {
	KDF             KDFParams `json:"kdf"`
	AuthDigest      []byte    `json:"authDigest"`
	WrappedVaultKey []byte    `json:"wrappedVaultKey"`
}

type FileInfo struct {
	ID           string    `json:"id"`
	Meta         []byte    `json:"meta"`
	Size         int64     `json:"size"`
	HasThumb     bool      `json:"hasThumb"`
	Complete     bool      `json:"complete"`
	CreatedAt    time.Time `json:"createdAt"`
	LastAppendAt time.Time `json:"lastAppendAt,omitzero"`
}

func (info *FileInfo) lastActivity() time.Time {
	if info.LastAppendAt.After(info.CreatedAt) {
		return info.LastAppendAt
	}
	return info.CreatedAt
}

type Store struct {
	root  string
	now   func() time.Time
	mu    sync.Mutex
	locks map[string]*sync.Mutex
}

func NewStore(root string) (*Store, error) {
	if err := os.MkdirAll(filepath.Join(root, "files"), 0o700); err != nil {
		return nil, fmt.Errorf("create data directory: %w", err)
	}
	return &Store{root: root, now: time.Now, locks: map[string]*sync.Mutex{}}, nil
}

func (s *Store) fileLock(id string) *sync.Mutex {
	s.mu.Lock()
	defer s.mu.Unlock()
	lock, ok := s.locks[id]
	if !ok {
		lock = &sync.Mutex{}
		s.locks[id] = lock
	}
	return lock
}

func writeFileAtomic(path string, data []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), ".tmp-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}

func (s *Store) accountPath() string { return filepath.Join(s.root, "account.json") }

func (s *Store) LoadAccount() (*Account, error) {
	data, err := os.ReadFile(s.accountPath())
	if errors.Is(err, os.ErrNotExist) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("read account: %w", err)
	}
	var account Account
	if err := json.Unmarshal(data, &account); err != nil {
		return nil, fmt.Errorf("parse account: %w", err)
	}
	return &account, nil
}

func (s *Store) CreateAccount(account Account) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, err := os.Stat(s.accountPath()); err == nil {
		return ErrAccountExists
	}
	data, err := json.Marshal(account)
	if err != nil {
		return err
	}
	return writeFileAtomic(s.accountPath(), data)
}

func (s *Store) fileDir(id string) (string, error) {
	if !idPattern.MatchString(id) {
		return "", ErrNotFound
	}
	return filepath.Join(s.root, "files", id), nil
}

func (s *Store) readInfo(dir string) (*FileInfo, error) {
	data, err := os.ReadFile(filepath.Join(dir, "info.json"))
	if errors.Is(err, os.ErrNotExist) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	var info FileInfo
	if err := json.Unmarshal(data, &info); err != nil {
		return nil, err
	}
	return &info, nil
}

func (s *Store) writeInfo(dir string, info *FileInfo) error {
	data, err := json.Marshal(info)
	if err != nil {
		return err
	}
	return writeFileAtomic(filepath.Join(dir, "info.json"), data)
}

func (s *Store) CreateFile(meta []byte) (string, error) {
	if len(meta) == 0 || len(meta) > maxMetaBytes {
		return "", ErrTooLarge
	}
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	id := hex.EncodeToString(raw)
	lock := s.fileLock(id)
	lock.Lock()
	defer lock.Unlock()
	dir := filepath.Join(s.root, "files", id)
	if err := os.Mkdir(dir, 0o700); err != nil {
		return "", err
	}
	if err := os.WriteFile(filepath.Join(dir, "content.bin"), nil, 0o600); err != nil {
		os.RemoveAll(dir)
		return "", err
	}
	info := &FileInfo{ID: id, Meta: meta, CreatedAt: s.now().UTC()}
	if err := s.writeInfo(dir, info); err != nil {
		os.RemoveAll(dir)
		return "", err
	}
	return id, nil
}

func (s *Store) AppendContent(id string, offset int64, body io.Reader) (int64, error) {
	dir, err := s.fileDir(id)
	if err != nil {
		return 0, err
	}
	lock := s.fileLock(id)
	lock.Lock()
	defer lock.Unlock()
	info, err := s.readInfo(dir)
	if err != nil {
		return 0, err
	}
	if info.Complete {
		return 0, ErrOffsetMismatch
	}
	file, err := os.OpenFile(filepath.Join(dir, "content.bin"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return 0, err
	}
	defer file.Close()
	stat, err := file.Stat()
	if err != nil {
		return 0, err
	}
	if stat.Size() != offset {
		return stat.Size(), ErrOffsetMismatch
	}
	written, err := io.Copy(file, io.LimitReader(body, maxAppendBytes+1))
	if err != nil {
		return offset + written, err
	}
	if written > maxAppendBytes {
		file.Truncate(offset)
		return offset, ErrTooLarge
	}
	if err := file.Sync(); err != nil {
		return offset + written, err
	}
	info.LastAppendAt = s.now().UTC()
	if err := s.writeInfo(dir, info); err != nil {
		return offset + written, err
	}
	return offset + written, nil
}

func (s *Store) UpdateMeta(id string, meta []byte) error {
	if len(meta) == 0 || len(meta) > maxMetaBytes {
		return ErrTooLarge
	}
	dir, err := s.fileDir(id)
	if err != nil {
		return err
	}
	lock := s.fileLock(id)
	lock.Lock()
	defer lock.Unlock()
	info, err := s.readInfo(dir)
	if err != nil {
		return err
	}
	info.Meta = meta
	return s.writeInfo(dir, info)
}

func (s *Store) PutThumb(id string, body io.Reader) error {
	dir, err := s.fileDir(id)
	if err != nil {
		return err
	}
	lock := s.fileLock(id)
	lock.Lock()
	defer lock.Unlock()
	info, err := s.readInfo(dir)
	if err != nil {
		return err
	}
	data, err := io.ReadAll(io.LimitReader(body, maxThumbBytes+1))
	if err != nil {
		return err
	}
	if len(data) > maxThumbBytes {
		return ErrTooLarge
	}
	if err := writeFileAtomic(filepath.Join(dir, "thumb.bin"), data); err != nil {
		return err
	}
	info.HasThumb = true
	return s.writeInfo(dir, info)
}

func (s *Store) Finalize(id string, size int64) error {
	dir, err := s.fileDir(id)
	if err != nil {
		return err
	}
	lock := s.fileLock(id)
	lock.Lock()
	defer lock.Unlock()
	info, err := s.readInfo(dir)
	if err != nil {
		return err
	}
	stat, err := os.Stat(filepath.Join(dir, "content.bin"))
	if err != nil || stat.Size() != size || size == 0 {
		return ErrIncomplete
	}
	info.Size = size
	info.Complete = true
	return s.writeInfo(dir, info)
}

func (s *Store) List() ([]FileInfo, error) {
	entries, err := os.ReadDir(filepath.Join(s.root, "files"))
	if err != nil {
		return nil, err
	}
	files := []FileInfo{}
	for _, entry := range entries {
		if !idPattern.MatchString(entry.Name()) {
			continue
		}
		dir := filepath.Join(s.root, "files", entry.Name())
		info, err := s.readInfoForListing(entry.Name(), dir)
		if err != nil {
			return nil, err
		}
		if info == nil {
			continue
		}
		if !info.Complete {
			if s.isStale(info) {
				s.removeStale(entry.Name(), dir)
			}
			continue
		}
		files = append(files, *info)
	}
	sort.Slice(files, func(i, j int) bool { return files[i].CreatedAt.After(files[j].CreatedAt) })
	return files, nil
}

func (s *Store) readInfoForListing(id, dir string) (*FileInfo, error) {
	lock := s.fileLock(id)
	lock.Lock()
	defer lock.Unlock()
	if _, err := os.Stat(dir); errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	info, err := s.readInfo(dir)
	if err != nil {
		return nil, fmt.Errorf("%w: file %s: %v", ErrUnreadableFile, id, err)
	}
	return info, nil
}

func (s *Store) isStale(info *FileInfo) bool {
	return !info.Complete && s.now().Sub(info.lastActivity()) > staleUploadAge
}

func (s *Store) removeStale(id, dir string) {
	lock := s.fileLock(id)
	lock.Lock()
	defer lock.Unlock()
	info, err := s.readInfo(dir)
	if err != nil || !s.isStale(info) {
		return
	}
	if err := os.RemoveAll(dir); err != nil {
		log.Printf("remove stale upload %s: %v", id, err)
	}
}

func (s *Store) OpenBlob(id, name string) (*os.File, error) {
	dir, err := s.fileDir(id)
	if err != nil {
		return nil, err
	}
	file, err := os.Open(filepath.Join(dir, name))
	if errors.Is(err, os.ErrNotExist) {
		return nil, ErrNotFound
	}
	return file, err
}

func (s *Store) Delete(id string) error {
	dir, err := s.fileDir(id)
	if err != nil {
		return err
	}
	lock := s.fileLock(id)
	lock.Lock()
	defer lock.Unlock()
	if _, err := os.Stat(dir); errors.Is(err, os.ErrNotExist) {
		return ErrNotFound
	}
	return os.RemoveAll(dir)
}
