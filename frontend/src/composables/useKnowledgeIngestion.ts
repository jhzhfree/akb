import {ref} from 'vue'
export function useKnowledgeIngestion(){
  const uploading=ref(false),stage=ref(''),message=ref(''),live=ref(false)
  return {uploading,stage,message,live}
}
