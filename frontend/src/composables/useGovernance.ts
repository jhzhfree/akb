import {ref} from 'vue'
import type {AssessmentRow,ConflictRow,ReviewRow} from '../types'
export function useGovernance(){
  return {pendingReviews:ref<ReviewRow[]>([]),conflicts:ref<ConflictRow[]>([]),assessments:ref<AssessmentRow[]>([])}
}
