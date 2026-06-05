import React from 'react'
import { useParams } from 'react-router-dom'

const GroupPage: React.FC = () => {
  const { id } = useParams<{ id: string }>()
  return (
    <div style={{ padding: '2rem', color: 'var(--color-text)' }}>
      <h2>Group: {id}</h2>
      <p style={{ color: 'var(--color-text-secondary)' }}>
        Implemented in Phase 7.
      </p>
    </div>
  )
}

export default GroupPage
