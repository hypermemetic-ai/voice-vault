/**
 * Request queue between the warm daemon's socket and its `handy` child.
 *
 * `handy --serve-transcription` answers one request at a time over stdin and
 * stdout, so the daemon keeps a FIFO queue with at most one request in flight.
 *
 * A slow reply is not a wedged process. When the host is busy (another
 * project's builds saturating the CPU, or a game holding the GPU), a warm model
 * can take longer than the client's 15 s bound while still making progress.
 * Killing it then throws away the loaded model, and the cold restart is slower
 * than the reply it interrupted. So the client's bound only decides when the
 * client falls back; the child is killed only when the in-flight request gets
 * no reply within the much longer wedge limit (`WHISPER_WEDGE_TIMEOUT_MS`).
 *
 * Requests whose client has hung up are dropped from the queue, so the model
 * does not transcribe audio nobody is waiting for.
 *
 * This module has no side effects and never spawns or listens on anything, so
 * the queue is unit-testable without starting the daemon.
 */

export const DEFAULT_WEDGE_TIMEOUT_MS = 120_000;

/**
 * Read the configured wedge limit at call time.
 *
 * @param {Record<string, string|undefined>} [env]
 * @returns {number}
 */
export function resolveWedgeTimeoutMs(env = process.env) {
  const parsed = Number.parseInt(env.WHISPER_WEDGE_TIMEOUT_MS ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_WEDGE_TIMEOUT_MS;
}

/**
 * @param {{
 *   write: (request: {id: string, wav: string}) => void,
 *   killChild: () => void,
 *   wedgeTimeoutMs?: number,
 *   log?: (...args: unknown[]) => void,
 * }} options
 */
export function createDaemonQueue({
  write,
  killChild,
  wedgeTimeoutMs = DEFAULT_WEDGE_TIMEOUT_MS,
  log = () => {},
}) {
  // Pending requests: { id, wav, resolve, reject }
  const queue = [];
  let inFlight = null;
  let wedgeTimer = null;
  let ready = false;
  let backend = "unknown";

  function settleInFlight() {
    clearTimeout(wedgeTimer);
    wedgeTimer = null;
    const current = inFlight;
    inFlight = null;
    return current;
  }

  function pump() {
    if (!ready || inFlight || queue.length === 0) return;

    inFlight = queue.shift();
    try {
      write({ id: inFlight.id, wav: inFlight.wav });
    } catch (err) {
      settleInFlight().reject(err);
      pump();
      return;
    }

    wedgeTimer = setTimeout(() => {
      const current = settleInFlight();
      log(`No reply for ${current.id} after ${wedgeTimeoutMs}ms; killing wedged handy process...`);
      current.reject(new Error(`Transcription wedged: no reply after ${wedgeTimeoutMs}ms`));
      killChild();
    }, wedgeTimeoutMs);
  }

  return {
    /**
     * Queue a transcription. `cancel` drops it if it has not reached `handy`
     * yet; a request already in flight runs to completion so the warm model
     * is never interrupted.
     *
     * @param {string} id
     * @param {string} wav
     */
    enqueue(id, wav) {
      let entry;
      const promise = new Promise((resolve, reject) => {
        entry = { id, wav, resolve, reject };
      });
      queue.push(entry);
      pump();

      return {
        promise,
        cancel() {
          const index = queue.indexOf(entry);
          if (index === -1) return false;
          queue.splice(index, 1);
          entry.reject(new Error("Client disconnected before transcription started"));
          return true;
        },
      };
    },

    /** The child announced it has loaded the model. */
    markReady(boundBackend) {
      ready = true;
      backend = boundBackend || "ready";
      pump();
    },

    /**
     * Route one JSON line from `handy` stdout. Returns false when the reply
     * matches no in-flight request.
     */
    handleReply(msg) {
      if (!inFlight || msg.id !== inFlight.id) return false;

      const current = settleInFlight();
      if (msg.ok === false) {
        current.reject(new Error(msg.error || "Handy transcription failed"));
      } else {
        current.resolve({
          ok: true,
          text: msg.text || "",
          transcribe_ms: msg.transcribe_ms,
          backend: msg.bound_backend || backend,
        });
      }
      pump();
      return true;
    },

    /** The child exited: fail everything it owed and wait for the next ready. */
    childExited(code) {
      ready = false;
      if (inFlight) {
        settleInFlight().reject(new Error(`Handy process exited unexpectedly (code ${code})`));
      }
      while (queue.length > 0) {
        queue.shift().reject(new Error("Handy process restarted"));
      }
    },

    status() {
      return {
        ready,
        backend,
        queueLength: queue.length,
        inFlight: !!inFlight,
      };
    },
  };
}
