package main

import (
    "context"
    "encoding/json"
    "log/slog"
    "net/http"
    "os"
    "os/signal"
    "syscall"
    "time"

    "github.com/go-chi/chi/v5"
    "github.com/jackc/pgx/v5/pgxpool"
    "github.com/redis/go-redis/v9"
)

type ReminderRequest struct {
    Prayer string `json:"prayer"`
    Time   string `json:"time"`
}

func main() {
    logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
    ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
    defer stop()

    dbURL := os.Getenv("GO_DATABASE_URL")
    if dbURL == "" {
        dbURL = "postgres://nafs_app:change_me_local_dev_only@localhost:5433/nafs?sslmode=disable"
    }
    db, err := pgxpool.New(ctx, dbURL)
    if err != nil {
        logger.Error("postgres connection failed", "err", err)
        os.Exit(1)
    }
    defer db.Close()

    redisURL := os.Getenv("REDIS_URL")
    if redisURL == "" {
        redisURL = "redis://localhost:6379"
    }
    opt, _ := redis.ParseURL(redisURL)
    rdb := redis.NewClient(opt)
    defer rdb.Close()

    if err := rdb.Ping(ctx).Err(); err != nil {
        logger.Error("redis ping failed", "err", err)
        os.Exit(1)
    }
    logger.Info("connected to PostgreSQL (5433) and Redis (6379)")

    r := chi.NewRouter()

    // Allow requests only from your local NAfs dashboard
    r.Use(func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
            w.Header().Set("Access-Control-Allow-Origin", "http://127.0.0.1:8081")
            w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
            if req.Method == http.MethodOptions {
                w.WriteHeader(http.StatusNoContent)
                return
            }
            next.ServeHTTP(w, req)
        })
    })

    r.Get("/health", func(w http.ResponseWriter, _ *http.Request) {
        w.Header().Set("Content-Type", "application/json")
        _, _ = w.Write([]byte(`{"status":"ok","service":"nafs-notifications-go"}`))
    })

    r.Post("/api/reminders", func(w http.ResponseWriter, req *http.Request) {
        var body ReminderRequest
        if err := json.NewDecoder(req.Body).Decode(&body); err != nil {
            http.Error(w, "invalid json", http.StatusBadRequest)
            return
        }
        key := "nafs:reminder:" + body.Prayer
        err := rdb.Set(req.Context(), key, body.Time, 24*time.Hour).Err()
        if err != nil {
            http.Error(w, "redis error", http.StatusInternalServerError)
            return
        }
        logger.Info("reminder scheduled in Redis", "prayer", body.Prayer, "time", body.Time)
        w.Header().Set("Content-Type", "application/json")
        _ = json.NewEncoder(w).Encode(map[string]any{"scheduled": true, "prayer": body.Prayer, "time": body.Time})
    })

    r.Get("/api/reminders", func(w http.ResponseWriter, req *http.Request) {
        keys, _ := rdb.Keys(req.Context(), "nafs:reminder:*").Result()
        result := make(map[string]string)
        for _, k := range keys {
            val, _ := rdb.Get(req.Context(), k).Result()
            result[k[len("nafs:reminder:"):]] = val
        }
        w.Header().Set("Content-Type", "application/json")
        _ = json.NewEncoder(w).Encode(result)
    })

    srv := &http.Server{Addr: "127.0.0.1:8082", Handler: r}
    go func() {
        logger.Info("Go notification service listening", "url", "http://127.0.0.1:8082/health")
        if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
            logger.Error("server error", "err", err)
        }
    }()

    <-ctx.Done()
    _ = srv.Shutdown(context.Background())
    logger.Info("notification worker shut down cleanly")
}