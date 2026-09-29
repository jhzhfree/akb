import {ref} from 'vue'
import type {DashboardGovernance,DashboardLifecycle,DashboardSummary} from '../types'
export function useWorkspaceOverview(){
  return {summary:ref<DashboardSummary|null>(null),lifecycle:ref<DashboardLifecycle|null>(null),governance:ref<DashboardGovernance|null>(null)}
}
