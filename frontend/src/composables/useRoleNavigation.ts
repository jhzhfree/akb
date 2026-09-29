import {computed,type Ref} from 'vue'
import {defaultLeafOf,locate,visibleLeaves,visibleSections} from '../navigation'
import type {NavSection} from '../navigation'
import {hasPermission} from '../permissions'
export function useRoleNavigation(active:Ref<string>,permissions:Ref<string[]|null|undefined>){
  const can=(code:string)=>hasPermission(permissions.value,code)
  const sections=computed(()=>visibleSections(can))
  const location=computed(()=>locate(active.value))
  const section=computed(()=>location.value?.section.key||'workbench')
  const leaves=(item:NavSection)=>visibleLeaves(item,can)
  const enter=(item:NavSection)=>{active.value=defaultLeafOf(item,can).key}
  return {can,sections,location,section,leaves,enter}
}
