import {computed,type Ref} from 'vue'
import type {KnowledgeBase} from '../types'
export function useWorkspaceContext(bases:Ref<KnowledgeBase[]>,knowledgeBaseId:Ref<number>){
  const activeBase=computed(()=>bases.value.find(item=>item.id===knowledgeBaseId.value))
  return {activeBase}
}
