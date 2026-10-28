// Appel réel avec un texte fictif : nécessite une clé configurée côté serveur.
// Usage : node scripts/test-ai-live.mjs [http://127.0.0.1:8080]
const base=process.argv[2] || 'http://127.0.0.1:8080';
async function json(path,options){const r=await fetch(base+path,options);const data=await r.json();if(!r.ok)throw Error(data.error||'Erreur HTTP '+r.status);return data;}
try{
 const status=await json('/api/ai/status');if(!status.configured)throw Error('OPENAI_API_KEY absente : aucun appel fournisseur effectué.');
 const state=await json('/api/state');const contract=state.data.contracts[0];if(!contract)throw Error('Créez au moins un contrat avant le test.');
 const result=await json('/api/ai/analyze',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({contractId:contract.id,text:'CONTRAT FICTIF DE TEST. Fourniture Internet à 300000 Ar par mois. Durée de douze mois. Préavis de 30 jours. En cas de litige, les parties recherchent une solution amiable.'})});
 if(!result.ai?.summary)throw Error('Réponse IA sans synthèse.');
 console.log('Connexion IA vérifiée : '+result.ai.provider+' / '+result.ai.model+'. Aucune fiche enregistrée.');
}catch(error){console.error(error.message);process.exitCode=1;}
