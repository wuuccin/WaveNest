const text = value => typeof value === 'string' && value.trim() ? value.trim() : null
const number = value => Number.isFinite(Number(value)) ? Number(value) : null

function candidateRows(frame) {
  const source = frame?.candidates ?? frame?.candidateList ?? frame?.candidateComparisons
  if (!Array.isArray(source)) return []
  return source.map((item, index) => {
    const reasons = Array.isArray(item?.rejectionReasons) ? item.rejectionReasons
      : Array.isArray(item?.reasons) ? item.reasons : []
    const nestId = text(item?.nestId ?? item?.homeNestId)
    const droneId = text(item?.droneId ?? item?.uavId)
    const selected = Boolean(item?.selected ?? item?.chosen)
      || (nestId && nestId === text(frame?.chosenNestId))
      || (droneId && droneId === text(frame?.chosenDroneId))
    const feasible = item?.feasible !== false && !['REJECTED', 'INFEASIBLE'].includes(item?.verdict)
    return {
      key: text(item?.candidateId) ?? droneId ?? nestId ?? `candidate-${index + 1}`,
      nestId,
      droneId,
      model: text(item?.model),
      distKm: number(item?.distKm ?? item?.distanceKm),
      reasons: reasons.map(text).filter(Boolean),
      selected,
      feasible
    }
  })
}

export function candidateAssessment(frame, assignedNestId = null) {
  if (!frame) return null
  const rows = candidateRows(frame)
  const reasons = Array.isArray(frame.rejectionReasons) ? frame.rejectionReasons.map(text).filter(Boolean) : []
  const countValue = Number(frame.candidateCount)
  const count = Number.isInteger(countValue) && countValue >= 0 ? countValue : (rows.length || null)
  if (count === null && !reasons.length && !rows.length) return null
  const chosenNestId = text(frame.chosenNestId) ?? rows.find(row => row.selected)?.nestId ?? text(assignedNestId)
  return {
    count: count ?? rows.length,
    rejectedCount: rows.length ? rows.filter(row => !row.feasible || row.reasons.length).length : reasons.length,
    reasons,
    chosenNestId,
    rows
  }
}
