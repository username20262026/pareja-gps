import { initializeApp } from 'firebase/app';
import { getAuth } from 'firebase/auth';
import { getFirestore } from 'firebase/firestore';

const firebaseConfig = {
  apiKey: 'AIzaSyB1XWhWuqLffxxKHdkBT45TR8ASfYU1nwA',
  authDomain: 'app-pareja-c1579.firebaseapp.com',
  projectId: 'app-pareja-c1579',
  storageBucket: 'app-pareja-c1579.firebasestorage.app',
  messagingSenderId: '803722038472',
  appId: '1:803722038472:web:1b22c8869c3422e9a629fd'
};

const app = initializeApp(firebaseConfig);
export const auth = getAuth(app);
export const db = getFirestore(app);
