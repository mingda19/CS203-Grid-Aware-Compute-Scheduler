/**
 * Phrases that try to change or reveal the assistant's rules ("ignore your instructions", "show
 * your system prompt", "pretend you are...").
 *
 * This is a tripwire for the obvious attempts, not a defence against a determined one: wording can
 * always be changed. It is used for two cheap, safe things. A user message containing one is
 * answered with the fixed off-topic reply instead of going to the model (topic.ts). An assistant
 * message in the history containing one is dropped, because the server cannot tell whether the
 * browser really received it from this server (validate.ts).
 */
const OVERRIDE_CUES: readonly RegExp[] = [
  // "ignore your previous instructions", "forget all the rules above". Needs a word like your/previous/all
  // so that "ignore a job's constraints" or "override the scheduler's rules" are left alone.
  /\b(?:ignore|disregard|forget|override|bypass)\b[^.?!\n]{0,40}\b(?:your|previous|prior|above|earlier|all|any|these|those|system)\b[^.?!\n]{0,20}\b(?:instructions?|rules?|prompts?|guidelines?)\b/i,
  /\b(?:system|developer|hidden|initial|original)\s+(?:prompt|message|instructions?)\b/i,
  /\b(?:jailbreak|DAN mode|developer mode)\b/i,
  /\b(?:pretend|imagine)\s+(?:that\s+)?(?:you\s+are|you're|to\s+be|you\s+have\s+no)\b/i,
  /\b(?:role-?play|from now on,? you)\b/i,
]

export function looksLikeOverride(text: string): boolean {
  return OVERRIDE_CUES.some((cue) => cue.test(text))
}
