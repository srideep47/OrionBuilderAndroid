import { useState } from 'react'
import './index.css'

export default function App() {
  const [count, setCount] = useState(0)
  return (
    <main className="app">
      <h1>Orion + React</h1>
      <p>Edit <code>src/App.tsx</code> and save to hot-reload.</p>
      <button onClick={() => setCount((c) => c + 1)}>count is {count}</button>
    </main>
  )
}
