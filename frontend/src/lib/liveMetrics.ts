import { useEffect, useRef, useState } from 'react';
import { api } from './api';
import type { LiveSnapshot, RouteTraffic } from './types';

export interface RatePoint {
  time: number;
  requestsPerSec: number;
  rateLimitedPerSec: number;
  errorsPerSec: number;
  avgLatencyMs: number | null;
}

/**
 * Converts two cumulative snapshots into per-second rates.
 * Counter resets (gateway restart) produce zero instead of negative rates.
 */
export function toRatePoint(previous: LiveSnapshot, current: LiveSnapshot): RatePoint {
  const seconds = Math.max((current.timestamp - previous.timestamp) / 1000, 0.001);
  const delta = (a: number, b: number) => Math.max(b - a, 0);
  const latencyCount = delta(previous.latencyCount, current.latencyCount);
  const latencyMs = delta(previous.latencyTotalMs, current.latencyTotalMs);
  return {
    time: current.timestamp,
    requestsPerSec: delta(previous.requests, current.requests) / seconds,
    rateLimitedPerSec: delta(previous.rateLimited, current.rateLimited) / seconds,
    errorsPerSec: delta(previous.status5xx, current.status5xx) / seconds,
    avgLatencyMs: latencyCount > 0 ? latencyMs / latencyCount : null,
  };
}

/** Polls /admin/v1/metrics/live and keeps a rolling window of rate points. */
export function useLiveMetrics(intervalMs = 2000, windowSize = 60) {
  const [points, setPoints] = useState<RatePoint[]>([]);
  const [latest, setLatest] = useState<LiveSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const previous = useRef<LiveSnapshot | null>(null);

  useEffect(() => {
    let cancelled = false;
    const tick = async () => {
      try {
        const snapshot = await api.get<LiveSnapshot>('/admin/v1/metrics/live');
        if (cancelled) return;
        if (previous.current) {
          const point = toRatePoint(previous.current, snapshot);
          setPoints((existing) => [...existing, point].slice(-windowSize));
        }
        previous.current = snapshot;
        setLatest(snapshot);
        setError(null);
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Metrics unavailable');
      }
    };
    void tick();
    const timer = window.setInterval(tick, intervalMs);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [intervalMs, windowSize]);

  const topRoutes: RouteTraffic[] = latest?.topRoutes ?? [];
  return { points, latest, topRoutes, error };
}
