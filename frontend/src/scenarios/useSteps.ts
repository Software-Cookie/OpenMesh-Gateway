import { useCallback, useRef, useState, type ReactNode } from 'react';

export type StepStatus = 'pending' | 'running' | 'done' | 'failed';

export interface Step {
  title: string;
  status: StepStatus;
  detail?: ReactNode;
}

export class StepFailure extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'StepFailure';
  }
}

export type StepFn = <T>(index: number, action: () => Promise<T>, detail?: (value: T) => ReactNode) => Promise<T>;

/**
 * Drives a scripted demo: each step is marked running, then done (with a detail line) or failed.
 * A thrown error fails the current step and stops the scenario.
 */
export function useSteps(titles: string[]) {
  const titlesRef = useRef(titles);
  titlesRef.current = titles;
  const initial = () => titlesRef.current.map((title): Step => ({ title, status: 'pending' }));

  const [steps, setSteps] = useState<Step[]>(initial);
  const [running, setRunning] = useState(false);
  const [outcome, setOutcome] = useState<'success' | 'failure' | null>(null);
  const current = useRef(0);

  const update = useCallback((index: number, patch: Partial<Step>) => {
    setSteps((list) => list.map((step, i) => (i === index ? { ...step, ...patch } : step)));
  }, []);

  const step: StepFn = useCallback(
    async (index, action, detail) => {
      current.current = index;
      update(index, { status: 'running', detail: undefined });
      const value = await action();
      update(index, { status: 'done', detail: detail?.(value) });
      return value;
    },
    [update],
  );

  const run = useCallback(
    async (script: (step: StepFn) => Promise<void>) => {
      setSteps(titlesRef.current.map((title) => ({ title, status: 'pending' })));
      setOutcome(null);
      setRunning(true);
      try {
        await script(step);
        setOutcome('success');
      } catch (error) {
        update(current.current, { status: 'failed', detail: error instanceof Error ? error.message : String(error) });
        setOutcome('failure');
      } finally {
        setRunning(false);
      }
    },
    [step, update],
  );

  return { steps, running, outcome, run };
}

export const sleep = (ms: number) => new Promise((resolve) => window.setTimeout(resolve, ms));

/** Repeats a check until it returns a value or the timeout passes. */
export async function poll<T>(check: () => Promise<T | null>, timeoutMs: number, intervalMs = 250): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await check();
    if (value !== null) return value;
    if (Date.now() > deadline) throw new StepFailure(`Timed out after ${Math.round(timeoutMs / 1000)} s`);
    await sleep(intervalMs);
  }
}
