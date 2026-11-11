import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import SignatureCallback from './SignatureCallback'

const signatureStatus=new URLSearchParams(window.location.search).get('signature')

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {signatureStatus==='reauthenticated'||signatureStatus==='failed'?<SignatureCallback status={signatureStatus}/>:<App />}
  </StrictMode>,
)
