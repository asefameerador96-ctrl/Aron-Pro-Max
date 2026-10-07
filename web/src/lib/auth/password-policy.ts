// Password policy for web roles (contract ChangePasswordRequest.new_password, docs/24 s8: web 12+ with upper, lower and digit).
// History (last 10) and the 24 h rule are the server's: it answers ERR_AUTH_PASSWORD_POLICY. Shared by the form and the BFF.
export const WEB_MIN_LENGTH = 12;
export const MAX_LENGTH = 128;

export type PasswordIssue = "required" | "too_short" | "too_long" | "needs_upper" | "needs_lower" | "needs_digit" | "same_as_old" | "mismatch" | "wrong_current";

export interface PasswordCheck {
  old?: PasswordIssue;
  next?: PasswordIssue;
  confirm?: PasswordIssue;
}

export function checkPasswords(oldPw: string, newPw: string, confirm: string): PasswordCheck {
  const out: PasswordCheck = {};
  if (oldPw.length === 0) out.old = "required";
  if (newPw.length === 0) out.next = "required";
  else if ([...newPw].length < WEB_MIN_LENGTH) out.next = "too_short"; // characters, not UTF-16 units
  else if ([...newPw].length > MAX_LENGTH) out.next = "too_long";
  else if (!/[A-Z]/.test(newPw)) out.next = "needs_upper";
  else if (!/[a-z]/.test(newPw)) out.next = "needs_lower";
  else if (!/\d/.test(newPw)) out.next = "needs_digit";
  else if (newPw === oldPw) out.next = "same_as_old";
  if (confirm !== newPw) out.confirm = confirm.length === 0 ? "required" : "mismatch";
  return out;
}

export const isValid = (c: PasswordCheck): boolean => !c.old && !c.next && !c.confirm;
