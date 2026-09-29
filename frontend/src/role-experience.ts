import type {UserRoleValue} from './types'
import {ROLE_BRIEF, ROLE_ACCENT} from './permissions'
import {userRoleLabel} from './types'

/** Role names affect presentation only; backend permission codes remain the authorization source of truth. */
export function roleExperience(role?:string|null,label?:string|null){
  const code=String(role||'') as UserRoleValue
  return {name:label||userRoleLabel(code),brief:ROLE_BRIEF[code]||'',accent:ROLE_ACCENT[code]||'var(--role-operator)'}
}
