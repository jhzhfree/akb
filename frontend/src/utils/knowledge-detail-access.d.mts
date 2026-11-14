export interface DetailAccess {
  detailsRestricted?: boolean
  omittedSections?: string[]
}
export function isDetailSectionRestricted(detail: DetailAccess | null | undefined, section: string): boolean
export function detailSectionCount(detail: DetailAccess | null | undefined, section: string, count: number): number | string
