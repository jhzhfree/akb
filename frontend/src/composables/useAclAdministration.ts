import {ref} from 'vue'
import type {AclAudit,AclChange,AclRole,AclSubject} from '../types'
export function useAclAdministration(){
  return {subjects:ref<AclSubject[]>([]),roles:ref<AclRole[]>([]),audits:ref<AclAudit[]>([]),changes:ref<AclChange[]>([])}
}
