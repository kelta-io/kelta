import { useMutation } from '@tanstack/react-query'
import { useApi } from '../context/ApiContext'
import { ApiError } from '../services/apiClient'

/**
 * Telehealth video hooks (slice 6) over /api/telehealth. The video-token
 * endpoints take an EMPTY body and return a room-scoped LiveKit grant; consent
 * is a separate 204 endpoint. Mirrors the useScheduling `useApi()` idiom — the
 * client comes from context, never a module-level singleton.
 */

export interface VideoToken {
  sessionId: string
  roomName: string
  /** LiveKit server URL (`wss://…`). */
  url: string
  /** Room-scoped JWT. */
  token: string
  /** ISO expiry of the grant. */
  expiresAt: string
}

/** The subject a video token is minted for. */
export type VideoTokenTarget =
  { kind: 'appointment'; id: string } | { kind: 'conversation'; id: string }

function tokenPath(target: VideoTokenTarget): string {
  return target.kind === 'appointment'
    ? `/api/telehealth/appointments/${target.id}/video-token`
    : `/api/telehealth/conversations/${target.id}/video-token`
}

/**
 * Reasons a video token can be refused, mapped from the endpoint's status codes.
 * The UI turns these into friendly copy rather than leaking the raw server error.
 */
export type VideoTokenErrorReason =
  | 'not-participant' // 403 not a participant
  | 'feature-off' // 403 telehealthEnabled off
  | 'outside-window' // 409 outside visit window / not CONFIRMED
  | 'budget-exhausted' // 429 video minute budget exhausted
  | 'unknown'

/**
 * Both 403 shapes (not-a-participant vs feature-off) share a status code; the
 * server distinguishes them in the message, so we sniff it. Anything we can't
 * classify falls back to a generic message.
 */
export function classifyVideoTokenError(error: unknown): VideoTokenErrorReason {
  if (error instanceof ApiError) {
    const msg = `${error.serverMessage} ${error.message}`.toLowerCase()
    if (error.status === 429) return 'budget-exhausted'
    if (error.status === 409) return 'outside-window'
    if (error.status === 403) {
      if (msg.includes('telehealth') || msg.includes('disabled') || msg.includes('not enabled')) {
        return 'feature-off'
      }
      return 'not-participant'
    }
  }
  return 'unknown'
}

export function useVideoToken() {
  const { apiClient } = useApi()
  return useMutation({
    // EMPTY body — the server derives everything from the path + caller identity.
    mutationFn: (target: VideoTokenTarget) => apiClient.post<VideoToken>(tokenPath(target)),
  })
}

export function useConsent() {
  const { apiClient } = useApi()
  return useMutation({
    mutationFn: (input: { sessionId: string; accepted: boolean }) =>
      apiClient.post<void>(`/api/telehealth/sessions/${input.sessionId}/consent`, {
        accepted: input.accepted,
      }),
  })
}
