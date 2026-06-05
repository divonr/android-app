import React from 'react'
import { useParams } from 'react-router-dom'

// P7 will implement the full streaming chat screen.
const ChatPage: React.FC = () => {
  const { id } = useParams<{ id: string }>()
  return (
    <div style={{ padding: '2rem', color: 'var(--color-text)' }}>
      <h2>Chat: {id}</h2>
      <p style={{ color: 'var(--color-text-secondary)' }}>
        Streaming chat implemented in Phase 7.
      </p>
    </div>
  )
}

export default ChatPage
