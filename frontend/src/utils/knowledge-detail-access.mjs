/** Explicit omissions win; older restricted responses without a list hide all ancillary sections. */
export function isDetailSectionRestricted(detail, section) {
  if (detail?.omittedSections?.includes(section)) return true
  return detail?.detailsRestricted === true && !detail.omittedSections?.length
}
export function detailSectionCount(detail, section, count) {
  return isDetailSectionRestricted(detail, section) ? '因权限未加载' : count
}
