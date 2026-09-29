import {ref} from 'vue'
import type {QueryResult} from '../types'
export function useKnowledgeQuery(){
  const query=ref('')
  const result=ref<QueryResult|null>(null)
  const asking=ref(false)
  return {query,result,asking}
}
