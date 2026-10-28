const $ = s => document.querySelector(s),
  esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  })[c]);
let today = new Date();
today.setHours(0, 0, 0, 0);
const iso = d => [d.getFullYear(), String(d.getMonth() + 1).padStart(2, '0'), String(d.getDate()).padStart(2, '0')].join('-'),
  plus = n => {
    let d = new Date(today);
    d.setDate(d.getDate() + n);
    return iso(d);
  },
  fmt = s => s ? new Date(s + 'T12:00:00').toLocaleDateString('fr-FR') : '—',
  money = n => Number(n).toLocaleString('fr-FR') + ' Ar',
  day = s => Math.round((new Date(s + 'T00:00:00') - today) / 864e5),
  deadline = c => {
    let d = new Date(c.end + 'T12:00:00');
    d.setDate(d.getDate() - Number(c.notice));
    return iso(d);
  };
let db,
  revision = 0;
let page = 'dashboard',
  q = '',
  filter = 'Tous';
const stages = ['Signalé', 'En analyse', 'En traitement', 'Clôturé'];
async function save() {
  const response = await fetch('/api/state', {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      revision,
      data: db
    })
  });
  const result = await response.json();
  if (!response.ok) throw Error(result.error || 'Enregistrement impossible');
  db = result.data;
  revision = result.revision;
}
let saving = false;
async function commit(action) {
  if (saving) return toast("Enregistrement en cours…");
  saving = true;
  const before = structuredClone(db);
  try {
    await action();
  } catch (error) {
    db = before;
    try {
      await refresh();
    } catch {}
    render();
    toast(error.message);
  } finally {
    saving = false;
  }
}
async function refresh() {
  const response = await fetch('/api/state');
  if (!response.ok) throw Error('Serveur indisponible');
  const result = await response.json();
  db = result.data;
  revision = result.revision;
}
function toast(t) {
  $('#toast').textContent = t;
  $('#toast').style.display = 'block';
  setTimeout(() => $('#toast').style.display = 'none', 3500);
}
function go(p) {
  if (page === 'ai' && p !== 'ai') aiRequest?.abort();
  page = p;
  q = '';
  filter = 'Tous';
  render();
}
function open(content) {
  $('#modal').innerHTML = '<button class="close" onclick="document.querySelector(\'#modal\').close()" aria-label="Fermer">✕</button>' + content;
  $('#modal').showModal();
}
function title(t, s) {
  return '<h1>' + t + '</h1><div class="muted">' + s + '</div>';
}
function badge(c) {
  return '<span class="badge ' + (c.status === 'Actif' ? '' : 'blue') + '">' + esc(c.status) + '</span>';
}
function alerts() {
  return db.contracts.filter(c => c.status === 'Actif' && c.end).map(c => ({
    c,
    date: c.renew === 'Oui' ? deadline(c) : c.end
  })).map(x => ({
    ...x,
    n: day(x.date)
  })).filter(x => x.n <= 30).sort((a, b) => a.n - b.n);
}
function alertBadge(n) {
  return '<span class="badge ' + (n <= 10 ? 'red' : 'orange') + '">' + (n < 0 ? Math.abs(n) + ' j dépassés' : n === 0 ? "Aujourd’hui" : 'Dans ' + n + ' j') + '</span>';
}
function alertRows(list) {
  return list.map(({
    c,
    date,
    n
  }) => '<tr><td><b>' + esc(c.supplier) + '</b><small>' + esc(c.name) + '</small></td><td>' + fmt(date) + '<small>' + (c.renew === 'Oui' ? 'Limite de préavis' : 'Fin du contrat') + '</small></td><td>' + alertBadge(n) + '</td><td><button class="link" onclick="detail(\'' + c.id + '\')">Consulter →</button></td></tr>').join('') || '<tr><td colspan="4" class="empty">Aucune échéance à surveiller.</td></tr>';
}
function render() {
  today = new Date();
  today.setHours(0, 0, 0, 0);
  for (const c of db.contracts) {
    c.status = automaticStatus(c);
  }
  const tabs = [['dashboard', '◫', 'Tableau de bord'], ['contracts', '▤', 'Contrats'], ['alerts', '◷', 'Échéances & alertes'], ['cases', '⚑', 'Litiges'], ['ai', '✧', 'Analyse des clauses'], ['settings', '⚙', 'Données & aide']];
  $('#nav').innerHTML = tabs.map(([id, ic, t]) => '<button class="' + (page === id ? 'active' : '') + '" onclick="go(\'' + id + '\')">' + ic + '　' + t + '</button>').join('');
  const active = db.contracts.filter(c => c.status === 'Actif'),
    a = alerts();
  let html = '';
  if (page === 'dashboard') {
    let total = active.filter(c => !c.details || c.details.currency==='MGA').reduce((s, c) => s + c.amount * ({
      Mensuel: 12,
      Trimestriel: 4,
      Annuel: 1
    }[c.frequency] || 1), 0);
    html = title('Pilotez vos contrats, sereinement.', 'Votre portefeuille fournisseurs au ' + today.toLocaleDateString('fr-FR') + '.') + '<div class="cards">' + [[active.length, 'Contrats actifs', 'Sur ' + db.contracts.length + ' contrats enregistrés'], [a.length, 'Échéances à surveiller', 'Préavis et fins dans les 30 jours'], [db.cases.filter(x => x.stage < 3).length, 'Litiges ouverts', 'Dossiers en cours de traitement'], [money(total), 'Engagement annuel estimé', 'Montants MGA renseignés, annualisés']].map(([v, t, s]) => '<div class="card"><span class="muted">' + t + '</span><strong style="' + (String(v).length > 12 ? 'font-size:23px' : '') + '">' + v + '</strong><small>' + s + '</small></div>').join('') + '</div><div class="grid"><div><div class="panel"><div class="row between"><h2>Les prochaines décisions</h2><button class="link" onclick="go(\'alerts\')">Tout voir →</button></div><div class="tablewrap"><table><thead><tr><th>Fournisseur</th><th>Date à respecter</th><th>Priorité</th><th></th></tr></thead><tbody>' + alertRows(a.slice(0, 4)) + '</tbody></table></div></div><div class="panel"><h2>Votre parcours de gestion</h2><div class="row"><span class="badge">1 · Enregistrer</span> → <span class="badge">2 · Vérifier les clauses</span> → <span class="badge">3 · Suivre les dates</span> → <span class="badge">4 · Agir</span></div></div></div><div><div class="panel"><h2>Répartition des contrats</h2>' + [...new Set(active.map(c => c.category))].map(cat => {
      let n = active.filter(c => c.category === cat).length;
      return '<div class="row between"><span>' + esc(cat) + '</span><b>' + n + '</b></div><div class="bar"><i style="width:' + n / Math.max(1, active.length) * 100 + '%"></i></div>';
    }).join('') + '</div><div class="notice"><b>Anticiper le renouvellement</b><br>Les alertes se calculent sur la limite de préavis lorsqu’un contrat prévoit une reconduction tacite.</div></div></div>';
  }
  if (page === 'contracts') html = title('Registre des contrats', 'Centralisez vos fournisseurs et leurs engagements.') + '<div class="filters"><input id="search" placeholder="Rechercher un fournisseur ou un contrat…" oninput="q=this.value;contractList()"><select onchange="filter=this.value;contractList()"><option>Tous</option><option>Actif</option><option>Terminé</option><option>Résilié</option><option>Brouillon</option></select></div><div class="panel tablewrap"><table><thead><tr><th>Contrat / fournisseur</th><th>Montant</th><th>Fin</th><th>Renouvellement auto.</th><th>Statut</th><th></th></tr></thead><tbody id="contractRows"></tbody></table></div>';
  if (page === 'alerts') html = title('Échéances & alertes', 'Rappels J−30 et J−10 avant la limite de préavis ou la fin du contrat.') + '<div class="notice warn">Les rappels sont affichés dans l’application. Aucun e-mail ni notification en arrière-plan n’est envoyé.</div><div class="panel tablewrap"><table><thead><tr><th>Contrat</th><th>Date de référence</th><th>Délai</th><th>Suivi</th></tr></thead><tbody>' + a.map(({
    c,
    date,
    n
  }) => {
    let key = c.id + ':' + date + ':' + (n <= 10 ? '10' : '30');
    return '<tr><td><b>' + esc(c.supplier) + '</b><small>' + esc(c.name) + '</small></td><td>' + fmt(date) + '<small>' + (c.renew === 'Oui' ? 'Préavis avant renouvellement' : 'Fin du contrat') + '</small></td><td>' + alertBadge(n) + '<small>' + (n < 0 ? 'Action en retard' : n <= 10 ? 'Rappel J−10' : 'Rappel J−30') + '</small></td><td><button onclick="ack(\'' + key + '\')">' + (db.done.includes(key) ? '✓ Pris en compte' : 'Marquer comme lu') + '</button> <button class="link" onclick="detail(\'' + c.id + '\')">Voir</button></td></tr>';
  }).join('') + '</tbody></table>' + (a.length ? '' : '<p class="empty">Aucune alerte.</p>') + '</div><div class="panel"><h2>Toutes les prochaines dates</h2><div class="timeline">' + active.filter(c => c.end).slice().sort((a, b) => a.end.localeCompare(b.end)).map(c => '<article><b>' + fmt(c.end) + ' · ' + esc(c.supplier) + '</b><br><small>Fin du contrat' + (c.renew === 'Oui' ? ' · Limite de préavis : ' + fmt(deadline(c)) : ' · Sans renouvellement automatique') + '</small></article>').join('') + '</div></div>';
  if (page === 'cases') html = title('Gestion des litiges', 'Du signalement à la résolution : chaque étape reste visible.') + '<div class="row" style="margin:22px 0"><button class="primary" onclick="newCase()">+ Signaler un litige</button></div><div class="kanban">' + stages.map((s, i) => '<div class="column"><h3>' + s + ' <span class="badge blue">' + db.cases.filter(x => x.stage === i).length + '</span></h3>' + db.cases.filter(x => x.stage === i).map(x => '<article class="ticket"><small class="muted">' + esc(x.id) + ' · ' + esc(db.contracts.find(c => c.id === x.contract)?.supplier || '') + '</small><h3>' + esc(x.title) + '</h3><p>' + esc(x.description) + '</p><b>' + money(x.amount) + '</b><p class="muted">Responsable : ' + esc(x.owner) + '</p><button onclick="caseDetail(\'' + x.id + '\')">Ouvrir le dossier →</button></article>').join('') + '</div>').join('') + '</div>';
  if (page === 'ai') html = title('Analyse IA du contrat', 'Repérez les passages à vérifier avant de prendre une décision.') + '<div class="notice"><b>Analyse par intelligence artificielle.</b> Selon la configuration du serveur, le texte est traité localement avec Ollama ou transmis à OpenAI. Relisez la fiche avant validation.</div><p id="aiStatus" class="muted" role="status">Vérification de la disponibilité de l’IA…</p><div class="grid"><div class="panel"><h2>Texte du contrat</h2><label for="aiContract">Associer à un contrat</label><select id="aiContract" onchange="loadAiDocuments()">' + db.contracts.map(c => '<option value="' + c.id + '">' + esc(c.name) + '</option>').join('') + '</select><p><label for="pdfFile">Importer un contrat PDF (10 Mo, 100 pages maximum)</label><input type="file" id="pdfFile" accept=".pdf,application/pdf" onchange="uploadPdf(this)"></p><div id="aiDocuments"></div><p><label for="textfile">Importer un fichier texte (.txt)</label><input type="file" id="textfile" accept=".txt,text/plain" onchange="loadText(this)"></p><label for="sourceText">Texte du contrat (extrait du PDF ou collé, 40 000 caractères maximum pour l’IA)</label><textarea id="sourceText" maxlength="40000" style="min-height:260px" placeholder="Collez les clauses du contrat…"></textarea><div class="row" style="margin-top:14px"><button id="analyzeButton" class="primary" onclick="analyze()" disabled>✧ Analyser avec l’IA</button><button onclick="example()">Charger un exemple</button></div></div><div class="panel" id="aiResult"><h2>Fiche d’analyse</h2><div class="empty">Importez du texte ou chargez l’exemple pour démarrer.</div></div></div><div class="panel"><h2>Analyses enregistrées</h2>' + (db.analysis.map((a, i) => '<p>' + esc(a.contract) + ' · ' + esc(a.date) + ' <button class="link" onclick="showAnalysis(' + i + ')">Consulter</button></p>').join('') || '<p class="muted">Aucune analyse enregistrée.</p>') + '</div>';
  if (page === 'settings') html = title('Données & mode d’emploi', 'Gestion des contrats avec Spring Boot et PostgreSQL.') + '<div class="grid"><div class="panel"><h2>Sauvegarde complète (PDF inclus)</h2><p>Conservez les données et les documents dans une archive ZIP.</p><button class="primary" onclick="exportFullBackup()">Télécharger la sauvegarde complète</button><p><label for="fullBackup">Restaurer une sauvegarde complète (.zip, 50 Mo maximum)</label><input id="fullBackup" type="file" accept=".zip,application/zip" onchange="restoreFullBackup(this)"></p><h2>Sauvegardes JSON et exports</h2><p>Les contrats, litiges et analyses sont conservés dans la base de données du serveur. Exportez une sauvegarde pour transférer vos données.</p><p class="notice warn">La sauvegarde JSON contient les données et les analyses, mais pas les fichiers PDF. Téléchargez les PDF séparément pour les conserver.</p><div class="row"><button class="primary" onclick="download(JSON.stringify(db,null,2),\'pacte-sauvegarde.json\',\'application/json\')">Exporter la sauvegarde</button><button onclick="csv()">Exporter les contrats CSV</button></div><p><label>Restaurer une sauvegarde Pacte (.json)</label><input type="file" accept=".json" onchange="restore(this)"></p><button onclick="reset()">Réinitialiser les données de démonstration</button></div><div class="panel"><h2>OCR et rappels automatiques</h2><div id="serviceStatus">Chargement…</div><h2>Les quatre livrables</h2><p><b>01 · Formulaire</b><br>Ajout, modification et consultation des contrats.</p><p><b>02 · Workflow litige</b><br>Signalement, analyse, traitement et clôture avec historique.</p><p><b>03 · Tableau de bord</b><br>Indicateurs, échéances et rappels calculés.</p><p><b>04 · Fiche d’analyse</b><br>Extraits des clauses, validation humaine et impression.</p></div></div><div class="notice">Données fictives. La validation d’une analyse est enregistrée comme une vérification utilisateur. Application sans connexion utilisateur. Les rappels e-mail nécessitent une configuration SMTP.</div>';
  $('#view').innerHTML = html;
  if (page === 'contracts') contractList();
  if (page === 'ai') { loadAiStatus(); loadAiDocuments(); }
  if (page === 'settings') loadServiceStatus();
}
function contractList() {
  let cs = db.contracts.filter(c => (filter === 'Tous' || c.status === filter) && (c.name + ' ' + c.supplier + ' ' + c.id).toLowerCase().includes(q.toLowerCase()));
  $('#contractRows').innerHTML = cs.map(c => '<tr><td><b>' + esc(c.name) + '</b><small>' + esc(c.id) + ' · ' + esc(c.supplier) + '</small></td><td>' + (c.amount==null?'Non renseigné':Number(c.amount).toLocaleString('fr-FR')+' '+(c.details?.currency||'MGA')) + '<small>' + esc(c.frequency) + '</small></td><td>' + fmt(c.end) + '</td><td>' + esc(c.renew) + (c.renew === 'Oui' ? '<small>Préavis ' + c.notice + ' jours</small>' : '') + '</td><td>' + badge(c) + '</td><td><button class="link" onclick="detail(\'' + c.id + '\')">Ouvrir →</button></td></tr>').join('') || '<tr><td colspan="6" class="empty">Aucun contrat trouvé.</td></tr>';
}
function input(label, name, value, type = 'text', extra = '') {
  return '<div><label for="f_' + name + '">' + label + '</label><input id="f_' + name + '" name="' + name + '" type="' + type + '" value="' + esc(value) + '" ' + extra + '></div>';
}
function select(label, name, value, options) {
  return '<div><label for="f_' + name + '">' + label + '</label><select id="f_' + name + '" name="' + name + '">' + options.map(o => '<option ' + (o === value ? 'selected' : '') + '>' + esc(o) + '</option>').join('') + '</select></div>';
}
const contractTypes = {distribution:'Contrat de distribution',commercial:'Contrat commercial',partenariat:'Contrat de partenariat',approvisionnement:"Contrat d’approvisionnement",services:'Contrat de prestation de services',autre:'Autre type de contrat'};
const specificFields = {
 distribution:{territory:'Territoire',exclusivity:'Exclusivité ou non-exclusivité',products:'Produits distribués',targets:'Objectifs de vente',storage:'Stockage',promotion:'Promotion'},
 commercial:{transaction:'Nature de la transaction',products:'Produits ou services',quantities:'Quantités',unitPrices:'Prix unitaires et devise',discounts:'Remises',delivery:'Livraison'},
 partenariat:{objectives:'Objectifs communs',contributions:'Contributions des parties',responsibilities:'Responsabilités',sharing:'Partage des coûts ou revenus (si applicable)',exit:'Conditions de sortie'},
 approvisionnement:{products:'Produits et références',minimum:'Quantités minimales',frequency:'Fréquence de livraison',delivery:'Délais de livraison',unitPrices:'Prix unitaires et devise',compliance:'Contrôle de conformité'},
 services:{description:'Nature de la prestation / objet',deliverables:'Documents contractuels / livrables',schedule:'Calendrier',acceptance:'Critères de réception',maintenance:'Maintenance (si applicable)',support:'Assistance (si applicable)',clientName:'Nom de l’entreprise cliente',clientAddress:'Adresse du siège social du Client',clientRepresentative:'Nom et titre du représentant du Client',providerAddress:'Adresse du Prestataire',discussionDate:'Date de l’entretien préalable',clientObligations:'Obligations du Client',place:'Lieu de signature'},
 autre:{customType:'Type libre',obligations:'Obligations',conditions:'Conditions particulières'}
};
function automaticStatus(c) {
 if(c.status==='Résilié') return 'Résilié';
 const date=iso(new Date());
 return c.end && c.end<date?'Terminé':c.start>date?'Brouillon':'Actif';
}
const currencies = [['MGA','🇲🇬','Ariary'],['EUR','🇪🇺','Euro'],['USD','🇺🇸','Dollar américain'],['GBP','🇬🇧','Livre sterling'],['CHF','🇨🇭','Franc suisse'],['CNY','🇨🇳','Yuan'],['JPY','🇯🇵','Yen'],['INR','🇮🇳','Roupie indienne'],['MUR','🇲🇺','Roupie mauricienne'],['ZAR','🇿🇦','Rand'],['CAD','🇨🇦','Dollar canadien'],['AUD','🇦🇺','Dollar australien']];
function currencySelect(value) {
 const options=currencies.some(([code])=>code===value)?currencies:[...currencies,[value,'🌐',value]];
 return '<div><label for="f_details_currency">Devise *</label><select required id="f_details_currency" name="details_currency">'+options.map(([code,flag,label])=>'<option value="'+esc(code)+'" '+(code===value?'selected':'')+'>'+flag+' '+esc(code)+' — '+esc(label)+'</option>').join('')+'</select></div>';
}
function newContract(id) {
 const original=db.contracts.find(c=>c.id===id);
 const c=structuredClone(original || {id:'CT-'+crypto.randomUUID(),name:'Prestation de démonstration',supplier:'Fournisseur Démo',category:'Services',owner:'Carlos',amount:1200000,frequency:'Annuel',start:iso(today),end:null,notice:0,renew:'Non',status:'Brouillon',penalty:'',notes:''});
 const d=c.details || {reference:original?c.id:'REF-'+iso(today).replaceAll('-','')+'-'+crypto.randomUUID().slice(0,8).toUpperCase(),type:'services',representative:'Rakoto Jean',department:'Achats',purpose:'Maintenance et assistance du matériel informatique pour le projet de démonstration.',signed:iso(today),currency:'MGA',payment:'Paiement sous 30 jours après réception de la facture.',clauses:'Confidentialité des informations échangées.',specific:{}};
 c.status=automaticStatus(c);
 let step=0, busy=false;
 const names=['Identification','Objet et obligations','Conditions financières','Durée et renouvellement','Clauses et risques','Prévisualisation du contrat'];
 const area=(label,key,value,required=false)=>'<div class="full"><label for="w_'+key+'">'+esc(label)+(required?' *':'')+'</label><textarea id="w_'+key+'" name="'+key+'" maxlength="10000" '+(required?'required':'')+'>'+esc(value)+'</textarea></div>';
 function capture() {
  for(const [k,v] of new FormData($('#contractForm'))) {
   if(k.startsWith('specific_')) d.specific[k.slice(9)]=v.trim();
   else if(k.startsWith('details_')) d[k.slice(8)]=v.trim() || (k==='details_signed'?null:'');
   else if(k!=='pdf') c[k]=v.trim();
  }
  c.status=automaticStatus(c);
 }
 function valid() {
  const form=$('#contractForm');
  for(const el of form.querySelectorAll('input[required],textarea[required]')) el.value=el.value.trim();
  if(!form.reportValidity()) return false;
  if(step===3 && c.end && c.end<c.start) {toast('La fin doit suivre la date de début.');return false;}
  return true;
 }
 function show() {
  let html='';
  if(step===0) html='<div class="full"><label for="contractType">Type de contrat *</label><select id="contractType" name="details_type" required><option value="">Choisir un type…</option>'+Object.entries(contractTypes).map(([k,v])=>'<option value="'+k+'" '+(d.type===k?'selected':'')+'>'+v+'</option>').join('')+'</select></div>'+input('Référence automatique','details_reference',d.reference,'text','readonly')+input('Intitulé *','name',c.name,'text','required maxlength="150"')+input('Fournisseur *','supplier',c.supplier,'text','required maxlength="120"')+input('Représentant','details_representative',d.representative,'text','maxlength="200"')+input('Responsable interne *','owner',c.owner,'text','required maxlength="200"')+input('Service concerné *','details_department',d.department,'text','required maxlength="200"')+input('Catégorie','category',c.category,'text','required maxlength="200"')+input('Statut automatique','status',c.status,'text','readonly');
  if(step===1) html=area('Objet du contrat','details_purpose',d.purpose,true)+Object.entries(specificFields[d.type]).map(([k,label],i)=>{const required=d.type==='services'?['description','clientName','clientAddress','clientRepresentative','providerAddress','discussionDate','clientObligations','place'].includes(k):i===0;return d.type==='services'&&k==='discussionDate'?input(label,'specific_'+k,d.specific[k]||'','date',required?'required':''):area(label,'specific_'+k,d.specific[k],required);}).join('');
  if(step===2) html=input('Montant estimé (facultatif)','amount',c.amount,'number','min="0" max="1000000000000" step="0.01"')+currencySelect(d.currency)+select('Périodicité du montant estimé','frequency',c.frequency,['Mensuel','Trimestriel','Annuel'])+area('Modalités de paiement','details_payment',d.payment);
  if(step===3) html=input('Date de signature','details_signed',d.signed,'date')+input('Date de début *','start',c.start,'date','required')+input('Date de fin (vide si durée indéterminée)','end',c.end,'date')+select('Reconduction','renew',c.renew,['Non','Oui'])+input('Préavis en jours *','notice',c.notice,'number','required min="0" max="730" step="1"')+'<p class="full muted">Sans date de fin, aucun rappel de fin automatique ne sera calculé.</p>';
  if(step===4) html=area('Clauses particulières','details_clauses',d.clauses)+area('Pénalités et risques à examiner','penalty',c.penalty)+area('Notes','notes',c.notes)+'<p class="full muted">La saisie ne garantit pas la validité juridique du contrat. Prévoyez une relecture adaptée.</p>';
  if(step===5) html='<div class="full"><p class="notice noprint">Relisez le contrat rédigé avant de l’enregistrer. Les informations absentes restent à compléter.</p>'+contractDocument({...c,details:d})+'<details class="noprint"><summary>Voir les données de la fiche</summary>'+contractSummary({...c,details:d})+'</details>'+'<h3>Documents PDF</h3><p>Les PDF peuvent être ajoutés à la fiche après enregistrement. Les documents existants sont conservés.</p>'+(original?'<div id="contractDocuments" data-contract="'+esc(c.id)+'"></div>':'')+'<p>Vérifiez le récapitulatif avant de confirmer.</p></div>';
  const content='<h2>'+(id?'Modifier le contrat':'Nouveau contrat fournisseur')+'</h2><p id="wizardStep" tabindex="-1">Étape '+(step+1)+' sur 6 · '+names[step]+'</p><progress aria-label="Progression du formulaire" max="6" value="'+(step+1)+'"></progress><ol class="wizardSteps">'+names.map((n,i)=>'<li '+(i===step?'aria-current="step"':'')+'>'+esc(n)+'</li>').join('')+'</ol><form id="contractForm"><div class="fields">'+html+'</div><p id="contractError" role="alert"></p><div class="row between"><button type="button" id="previous" '+(step===0?'disabled':'')+'>Précédent</button><button class="primary" type="submit">'+(step===5?'Enregistrer le contrat':step===4?'Prévisualiser le contrat →':'Suivant →')+'</button></div></form>';
  if(!$('#modal').open) open(content); else $('#modal').innerHTML='<button class="close" onclick="document.querySelector(\'#modal\').close()" aria-label="Fermer">✕</button>'+content;
  $('#previous').onclick=()=>{capture();step--;show();};
  if(step===0) $('#contractType').onchange=()=>capture();
  if(step===5 && original) loadContractDocuments(c.id);
  $('#contractForm').onsubmit=async e=>{
   e.preventDefault();if(busy)return;capture();if(!valid())return;
   if(step<5){step++;show();$('#wizardStep').focus();return;}
   c.amount=c.amount===''||c.amount==null?null:Number(c.amount);c.end=c.end||null;c.notice=Number(c.notice);
   c.details={...d,specific:Object.fromEntries(Object.keys(specificFields[d.type]).filter(k=>d.specific[k]?.trim()).map(k=>[k,d.specific[k]]))};
   busy=true;const before=structuredClone(db);$('#contractForm button.primary').disabled=true;
   try {
    if(db.contracts.some(x=>x.id!==c.id && x.details?.reference.trim().toLowerCase()===d.reference.toLowerCase())) throw Error('Référence déjà utilisée.');
    if(original) db.contracts[db.contracts.findIndex(x=>x.id===id)]=c;else db.contracts.push(c);
    await save();$('#modal').close();render();detail(c.id);toast('Contrat enregistré avec succès.');
   } catch(error){db=before;try{await refresh();}catch{}$('#contractError').textContent=error.message;}
   finally{busy=false;if($('#contractForm button.primary'))$('#contractForm button.primary').disabled=false;}
  };
 }
 show();
}
function contractSummary(c) {
 const d=c.details;
 const rows=[['Montant estimé',c.amount==null||c.amount===''?'Non renseigné':Number(c.amount).toLocaleString('fr-FR')+' '+(d?.currency||'MGA')],['Début',fmt(c.start)],['Fin',c.end?fmt(c.end):'Durée indéterminée'],['Responsable',c.owner],['Reconduction',c.renew],['Préavis',c.notice+' jours']];
 if(d) rows.unshift(['Référence',d.reference],['Intitulé',c.name],['Fournisseur',c.supplier],['Statut',c.status],['Type',contractTypes[d.type]],['Représentant',d.representative],['Service',d.department],['Objet',d.purpose],['Signature',fmt(d.signed)],['Paiement',d.payment],['Clauses particulières',d.clauses],...Object.entries(specificFields[d.type]||{}).map(([k,label])=>[label,d.specific[k]]));
 return '<dl class="contractSummary">'+rows.map(([label,value])=>'<div><dt>'+esc(label)+'</dt><dd>'+esc(value||'Non renseigné')+'</dd></div>').join('')+'</dl>';
}
function contractDocument(c) {
 const d=c.details||{}, text=value=>esc(value||'À compléter'), paragraph=value=>'<p class="contractText">'+text(value)+'</p>';
 const article=(title,body)=>'<section><h3>'+title+'</h3>'+body+'</section>';
 const obligations=Object.entries(specificFields[d.type]||{}).filter(([key])=>d.specific?.[key]).map(([key,label])=>'<p class="contractText"><strong>'+esc(label)+' :</strong> '+text(d.specific[key])+'</p>').join('');
 const amount=c.amount==null||c.amount===''?'Le montant et les conditions financières restent à convenir entre les parties.':'Le montant estimé est de '+Number(c.amount).toLocaleString('fr-FR')+' '+text(d.currency||'MGA')+' (périodicité : '+text(c.frequency)+').';
 return '<article class="contractPaper" aria-label="Contrat rédigé"><div class="contractHeading"><p>CONTRAT FOURNISSEUR</p><h2>'+text(contractTypes[d.type]||'Contrat')+'</h2><p>'+text(c.name)+'</p><small>Référence : '+text(d.reference||c.id)+'</small></div>'+
 '<p><strong>Entre les soussignés :</strong></p><p>Le donneur d’ordre, dont la dénomination et l’adresse sont à compléter, service <strong>'+text(d.department)+'</strong>, ayant pour responsable interne <strong>'+text(c.owner)+'</strong>, ci-après « le Client » ;</p><p>Et <strong>'+text(c.supplier)+'</strong>, représenté par <strong>'+text(d.representative)+'</strong>, dont l’adresse et les identifiants sont à compléter, ci-après « le Fournisseur ».</p><p>Les parties conviennent des dispositions suivantes :</p>'+
 article('Article 1 — Objet du contrat',paragraph(d.purpose||c.name))+
 article('Article 2 — Prestations et obligations',obligations||paragraph('Les obligations des parties sont à compléter.'))+
 article('Article 3 — Conditions financières','<p>'+amount+'</p>'+paragraph(d.payment))+
 article('Article 4 — Durée et renouvellement','<p>Le contrat prend effet le <strong>'+text(fmt(c.start))+'</strong>'+ (c.end?' et arrive à échéance le <strong>'+text(fmt(c.end))+'</strong>.':'. Il est conclu pour une durée indéterminée.')+'</p><p>'+(c.renew==='Oui'?'Une reconduction est prévue. Le préavis renseigné est de '+text(c.notice)+' jours. Les modalités et la durée de reconduction restent à préciser.':'Aucune reconduction automatique n’est prévue.')+'</p>')+
 article('Article 5 — Clauses particulières',paragraph(d.clauses))+
 article('Article 6 — Pénalités et risques',paragraph(c.penalty||'Aucune pénalité spécifique renseignée.'))+
 '<p>Fait à ____________________, le '+(d.signed?text(fmt(d.signed)):'____________________')+', en deux exemplaires.</p><div class="contractSignatures"><div><strong>Pour le Client</strong><p>Nom, qualité et signature</p><div></div></div><div><strong>Pour le Fournisseur</strong><p>Nom, qualité et signature</p><div></div></div></div></article>';
}
function previewContract(id) {
 const c=db.contracts.find(x=>x.id===id);
 if(!c) return;
 open('<div class="noprint"><h2>Prévisualisation du contrat</h2><p>Document à relire et à compléter avant signature.</p></div>'+contractDocument(c)+'<div class="row noprint"><button class="primary" onclick="window.print()">Imprimer / enregistrer en PDF</button><button onclick="detail(\''+c.id+'\')">Retour à la fiche</button></div>');
}
function detail(id) {
  let c = db.contracts.find(x => x.id === id);
<<<<<<< HEAD
  open('<span class="eyebrow">' + esc(c.id) + '</span><h2>' + esc(c.name) + '</h2><p>' + esc(c.supplier) + ' · ' + badge(c) + '</p>' + contractSummary(c) + (c.renew === 'Oui' && c.end ? '<div class="notice warn">Date limite de préavis : <b>' + fmt(deadline(c)) + '</b><br>Rappels affichés 30 et 10 jours avant cette limite.</div>' : '') + '<h3>Pénalités</h3><p>' + esc(c.penalty || 'Non renseignées') + '</p><h3>Notes</h3><p>' + esc(c.notes || 'Aucune') + '</p><h3>Documents PDF</h3><label for="contractPdf">Ajouter le contrat PDF (10 Mo maximum)</label><input id="contractPdf" type="file" accept=".pdf,application/pdf" onchange="uploadPdf(this,\'' + id + '\')"><div id="contractDocuments" data-contract="' + esc(id) + '"></div><div class="row"><button class="primary" onclick="document.querySelector(\'#modal\').close();newContract(\'' + id + '\')">Modifier</button><button onclick="previewContract(\'' + id + '\')">Prévisualiser le contrat</button><button onclick="window.print()">Imprimer la fiche</button></div>');
=======
  open('<span class="eyebrow">' + esc(c.id) + '</span><h2>' + esc(c.name) + '</h2><p>' + esc(c.supplier) + ' · ' + badge(c) + '</p>' + contractSummary(c) + (c.renew === 'Oui' && c.end ? '<div class="notice warn">Date limite de préavis : <b>' + fmt(deadline(c)) + '</b><br>Rappels affichés 30 et 10 jours avant cette limite.</div>' : '') + '<h3>Pénalités</h3><p>' + esc(c.penalty || 'Non renseignées') + '</p><h3>Notes</h3><p>' + esc(c.notes || 'Aucune') + '</p><h3>Documents PDF</h3><label for="contractPdf">Ajouter le contrat PDF (10 Mo maximum)</label><input id="contractPdf" type="file" accept=".pdf,application/pdf" onchange="uploadPdf(this,\'' + id + '\')"><div id="contractDocuments" data-contract="' + esc(id) + '"></div><div class="row"><button class="primary" onclick="document.querySelector(\'#modal\').close();newContract(\'' + id + '\')">Modifier</button><button onclick="window.print()">Imprimer la fiche</button></div>');
  $('#modal .row').prepend('<button onclick="generateContractPdf(\'' + id + '\')">Générer le projet de contrat PDF</button>');
>>>>>>> b3b9c5b (contrat de prestation de service)
  loadContractDocuments(id);
}
function generateContractPdf(id) {
 const c=db.contracts.find(x=>x.id===id);if(!c)return;
 const d=c.details||{},labels=specificFields[d.type]||{},type=contractTypes[d.type]||'Contrat fournisseur';
 if(d.type==='services'){generateServiceContractPdf(c);return;}
 const value=v=>{const s=String(v??'').trim();return !s||s.toLowerCase().includes('conditions de démonstration à vérifier')?'[À compléter]':s;};
 const line=(label,text)=>`<p><b>${esc(label)} :</b> ${esc(text)}</p>`;
 const article=(title,body)=>`<section><h2>${title}</h2>${body}</section>`;
 const specifics=Object.entries(labels).map(([k,label])=>line(label,value(d.specific?.[k]))).join('')||'<p>[À compléter selon le type de contrat.]</p>';
 const amount=c.amount==null?'[À compléter]':Number(c.amount).toLocaleString('fr-FR')+' '+(d.currency||'MGA')+' · '+(c.frequency||'périodicité à préciser');
 const duration='Prise d’effet prévue le '+fmt(c.start)+'. '+(c.end?'Échéance prévue le '+fmt(c.end)+'.':'Date de fin : [à préciser].')+' '+(c.renew==='Oui'?'Reconduction indiquée, modalités à confirmer. Préavis saisi : '+Number(c.notice||0)+' jours.':'Aucune reconduction enregistrée.');
 const html=`<!doctype html><html lang="fr"><head><meta charset="utf-8"><title>Projet de contrat — ${esc(c.name)}</title><style>
 @page{size:A4;margin:20mm 18mm}*{box-sizing:border-box}body{font:11pt/1.55 Georgia,"Times New Roman",serif;color:#182b31;margin:0}.page{max-width:820px;margin:auto}.top{font:9pt Arial,sans-serif;color:#126956;letter-spacing:2px;text-transform:uppercase;border-bottom:2px solid #126956;padding-bottom:8px}.cover{min-height:240mm;display:flex;flex-direction:column;justify-content:center;text-align:center;page-break-after:always}.cover h1{font:700 26pt Arial,sans-serif;color:#173b3c;margin:16px}.cover h2{font:15pt Arial,sans-serif;color:#126956}.alert{font:10pt Arial,sans-serif;color:#7b4b00;background:#fff2d6;border:1px solid #e7c879;padding:12px;margin:20px 0}h1{font:20pt Arial,sans-serif;color:#173b3c}h2{font:14pt Arial,sans-serif;color:#126956;margin:24px 0 8px;break-after:avoid}h3{font:11pt Arial,sans-serif}.parties,.sign{display:grid;grid-template-columns:1fr 1fr;gap:18px}.party{border:1px solid #bacac4;padding:12px}p{margin:7px 0;white-space:pre-wrap}section{break-inside:avoid}.sign{margin-top:35px}.sign div{border-top:1px solid #61736e;min-height:80px;padding-top:8px}footer{margin-top:30px;padding-top:8px;border-top:1px solid #c9d4d0;font:8pt Arial,sans-serif;color:#667773}.actions{position:fixed;top:12px;right:12px;font:14px Arial,sans-serif}.actions button{padding:10px 16px;background:#126956;color:white;border:0;border-radius:6px}@media print{.actions{display:none}.page{max-width:none}}@media screen{body{background:#eef2f0}.page{background:white;padding:24mm;box-shadow:0 2px 18px #0002;margin:20px auto}.cover{min-height:250mm}}
 </style></head><body><div class="actions"><button onclick="window.print()">Imprimer / enregistrer en PDF</button></div><main class="page"><div class="cover"><div class="top">Pacte · LegalTech · Madagascar</div><h1>${esc(type.toUpperCase())}</h1><h2>${esc(value(c.name))}</h2><p>Référence : ${esc(value(d.reference||c.id))}</p><div class="alert"><b>PROJET À COMPLÉTER ET À FAIRE RELIRE</b><br>Canevas généré depuis les informations saisies. Il ne certifie pas la conformité juridique et ne doit pas être signé avant vérification.</div></div>
 <div class="top">Projet de contrat fournisseur · ${esc(value(d.reference||c.id))}</div><h1>${esc(type)}</h1><p>Les informations manquantes sont signalées entre crochets.</p>
 ${article('Identification des parties','<div class="parties"><div class="party"><h3>Acheteur / Client</h3><p>[Dénomination, forme, siège et immatriculation à compléter]</p><p>Représenté par : [nom, qualité et pouvoir à compléter]</p></div><div class="party"><h3>Fournisseur / Prestataire</h3><p><b>'+esc(value(c.supplier))+'</b></p><p>Forme, siège, RCS, NIF et STAT : [à compléter]</p><p>Représentant déclaré : '+esc(value(d.representative))+'</p></div></div>')}
 ${article('Article 1 — Objet',line('Objet du contrat',value(d.purpose)))}
 ${article('Article 2 — Conditions propres au contrat',specifics)}
 ${article('Article 3 — Obligations des parties','<p>Le Fournisseur exécute les livraisons ou prestations décrites et signale sans délai les difficultés d’exécution. L’Acheteur communique les informations nécessaires, vérifie la livraison ou la prestation selon les critères convenus et règle les sommes dues selon les modalités convenues.</p><p>Chaque partie reste responsable des obligations légales, fiscales, sociales et professionnelles qui lui sont applicables.</p>')}
 ${article('Article 4 — Prix et paiement',line('Montant estimatif saisi',amount)+line('Modalités saisies',value(d.payment))+'<p>Préciser si le prix inclut taxes, frais, transport, assurance et autres coûts, ainsi que les pièces et échéances de facturation.</p>')}
 ${article('Article 5 — Livraison, exécution et réception','<p>Le lieu, les dates, les délais, les critères de conformité et la procédure de réception doivent être définis par écrit.</p>'+Object.entries(labels).filter(([k])=>['delivery','frequency','schedule','acceptance','compliance'].includes(k)).map(([k,label])=>line(label,value(d.specific?.[k]))).join(''))}
 ${article('Article 6 — Durée et renouvellement',line('Durée prévue',duration))}
 ${article('Article 7 — Pénalités et clauses particulières',line('Pénalités saisies',value(c.penalty))+line('Clauses saisies',value(d.clauses))+'<p>Vérifier les conditions d’application, calculs et plafonds avant signature.</p>')}
 ${article('Article 8 — Droit applicable et différends','<p>Projet à confirmer : application du droit malgache, notamment de la loi n° 66-003 relative à la théorie générale des obligations, dans sa version en vigueur, sous réserve des textes spéciaux applicables à l’activité et aux parties.</p><p>Préciser le règlement amiable et, à défaut d’accord, la juridiction ou le mode de règlement compétent après vérification des règles applicables.</p>')}
 ${article('Article 9 — Résiliation et force majeure','<p>Les motifs de résiliation, délais de mise en demeure, effets de la fin du contrat et conséquences d’un événement de force majeure restent à compléter selon la situation et les textes applicables.</p>')}
 ${article('Article 10 — Documents contractuels et modifications','<p>Identifier et joindre les annexes et spécifications techniques. Préciser l’ordre de priorité entre documents et formaliser les modifications par écrit.</p>')}
 <h2>Signatures — après complétion et validation</h2><p>Fait à [lieu], le [date], en [nombre] exemplaires.</p><div class="sign"><div>Pour l’Acheteur / Client<br>Nom, qualité, signature et cachet</div><div>Pour le Fournisseur / Prestataire<br>Nom, qualité, signature et cachet</div></div><footer>Généré le ${new Date().toLocaleDateString('fr-FR')}. Canevas LegalTech non certifié juridiquement. Références et clauses à vérifier selon l’activité, le statut des parties, les règles sectorielles et le marché.</footer></main></body></html>`;
 const win=window.open('','_blank');if(!win){toast('Autorisez les fenêtres contextuelles pour générer le PDF.');return;}
 win.document.open();win.document.write(html);win.document.close();win.focus();setTimeout(()=>win.print(),400);
}
function generateServiceContractPdf(c) {
 const d=c.details||{},s=d.specific||{};
 const value=v=>{const text=String(v??'').trim();return !text||text.toLowerCase().includes('conditions de démonstration à vérifier')?'[À compléter]':text;};
 const para=text=>`<p>${text}</p>`;
 const field=(key,fallback='[À compléter]')=>esc(value(s[key]||fallback));
 const signatureDate=d.signed?fmt(d.signed):'[date à compléter]';
 const discussionDate=s.discussionDate?fmt(s.discussionDate):'[date à compléter]';
 const amount=c.amount==null?'[Montant total convenu]':Number(c.amount).toLocaleString('fr-FR')+' '+(d.currency||'MGA');
 const serviceDescription=value(s.description||d.purpose);
 const html=`<!doctype html><html lang="fr"><head><meta charset="utf-8"><title>Contrat de prestation de service</title><style>
 @page{size:A4;margin:22mm 18mm}*{box-sizing:border-box}body{margin:0;color:#111;font:12pt/1.48 Georgia,"Times New Roman",serif}.paper{max-width:820px;margin:auto;padding:8mm 4mm}p{margin:0 0 14px;white-space:pre-wrap}.lead{margin-bottom:16px}.lead b,.article b{font-weight:700}.article{margin:27px 0 52px;break-inside:avoid}.article h2{font:bold 12pt/1.4 Arial,sans-serif;margin:0 0 15px}.article p{margin-bottom:14px}.intro{margin:18px 0}.signatures{page-break-before:always;min-height:230mm;display:grid;grid-template-columns:1fr 1fr;gap:28px;padding-top:28mm;text-align:center}.signatures div{display:flex;flex-direction:column;justify-content:space-between;min-height:185mm}.signatures b{font-weight:700}.actions{position:fixed;right:16px;top:12px;font:14px Arial,sans-serif}.actions button{background:#126956;color:white;border:0;border-radius:6px;padding:10px 16px}@media print{.actions{display:none}.paper{max-width:none;padding:0}}@media screen{body{background:#eef2f0}.paper{background:white;min-height:297mm;padding:22mm 18mm;box-shadow:0 2px 18px #0002;margin:20px auto}}
 </style></head><body><div class="actions"><button onclick="window.print()">Imprimer / enregistrer en PDF</button></div><main class="paper">
 <p><b>Entre :</b></p>
 <p>${field('clientName',"[Nom de l’entreprise]")}, ayant son siège social à ${field('clientAddress','[Adresse]')}, représentée par ${field('clientRepresentative','[Nom et titre du représentant de l’entreprise]')}, ci-après dénommée « Client », d’une part,</p>
 <p><b>Et :</b></p>
 <p>${esc(value(c.supplier))}, domicilié à ${field('providerAddress','[Adresse]')}, ci-après dénommé « Prestataire », d’autre part,</p>
 <p>Étant préalablement rappeler que :</p>
 <p style="padding-left:28px">- &nbsp; En date du ${esc(discussionDate)}, les parties ont eu un entretien qui a abouti à la conclusion du présent contrat de prestation de service entre les deux parties.</p>
 <p class="intro">Il a été convenu ce qui suit :</p>
 <section class="article"><h2>Article 1 : Objet du contrat</h2>${para('Le présent contrat a pour objet '+esc(value(d.purpose)))}</section>
 <section class="article"><h2>Article 2 : Documents contractuels</h2>${para('Les deux parties reconnaissent le caractère contractuel de la réalisation de la prestation.')}</section>
 <section class="article"><h2>Article 3 : Durée du contrat</h2>${para('Ce contrat est valable à partir de la date de signature ('+signatureDate+') et reste en vigueur jusqu’à la réalisation complète des services convenus, sauf résiliation anticipée conformément aux conditions énoncées dans le présent contrat.')}</section>
 <section class="article"><h2>Article 4 : Nature de la prestation</h2>${para('La prestation de services faisant l’objet du présent contrat consiste à '+esc(serviceDescription))}</section>
 <section class="article"><h2>Article 5 : Modalités financières</h2>${para('Le Client s’engage à payer au Prestataire la somme de '+esc(amount)+'.')}</section>
 <section class="article"><h2>Article 6 : Obligations et responsabilités</h2>${para('Les obligations du prestataire sont des obligations de résultat et satisfaction sans lesquels, le client pourrait décider de rompre le contrat.')}${para('Le client est tenu de '+field('clientObligations')+'.')}${para('Chaque partie s’engage à respecter les termes et conditions énoncés dans ce contrat et à faire preuve de bonne foi dans l’exécution de ses obligations respectives.')}</section>
 <section class="article"><h2>Article 7 : Confidentialité</h2>${para('Les deux parties s’engagent à maintenir la confidentialité des informations échangées dans le cadre de ce contrat, sauf accord contraire écrit.')}</section>
 <section class="article"><h2>Article 8 : Résiliation</h2>${para('En cas de non-respect des termes du contrat par l’une des parties, l’autre partie peut résilier le contrat avec un préavis écrit de ['+Number(c.notice||0)+'] jours.')}</section>
 <section class="article"><h2>Article 9 : Litiges et droit applicable</h2>${para('En cas de litige découlant de l’interprétation ou de l’exécution du présent contrat, les parties s’engagent à chercher une solution amiable. À défaut, le litige sera soumis à la juridiction compétente.')}${para('Droit applicable : [à confirmer selon le droit malgache et la situation des parties].')}</section>
 <section class="article"><h2>Article 10 : [Article du modèle à compléter]</h2>${para('[Le texte de cet article ne figure pas dans les captures transmises.]')}</section>
 <section class="article"><h2>Article 11 : [Article du modèle à compléter]</h2>${para('[Le texte de cet article ne figure pas dans les captures transmises.]')}</section>
 <section class="article"><h2>Article 12 – Signature</h2>${para('En signant ce contrat, les deux parties reconnaissent avoir lu, compris et accepté tous les termes et conditions énoncés.')}${para('Fait à '+field('place')+', le '+signatureDate+'.')}${para('En deux (02) exemplaires originaux dont un pour le Client et un pour le Prestataire.')}</section>
 <div class="signatures"><div><p>Pour le Client</p><b>${field('clientRepresentative','(Nom et prénom du Représentant)')}</b></div><div><p>Pour le Prestataire</p><b>${esc(value(d.representative||c.supplier))}</b></div></div>
 </main></body></html>`;
 const win=window.open('','_blank');if(!win){toast('Autorisez les fenêtres contextuelles pour générer le PDF.');return;}
 win.document.open();win.document.write(html);win.document.close();win.focus();setTimeout(()=>win.print(),400);
}
function ack(k) {
  return commit(async () => {
    if (!db.done.includes(k)) db.done.push(k);
    await save();
    render();
  });
}
function newCase() {
  open('<h2>Signaler un litige</h2><form id="caseForm"><label>Contrat concerné</label><select name="contract">' + db.contracts.map(c => '<option value="' + c.id + '">' + esc(c.supplier + ' — ' + c.name) + '</option>').join('') + '</select><p><label>Objet *</label><input name="title" required></p><p><label>Description *</label><textarea name="description" required></textarea></p><div class="fields">' + input('Montant en jeu (Ar)', 'amount', 0, 'number', 'min="0" required') + input('Responsable *', 'owner', '', 'text', 'required') + '</div><p><button class="primary">Enregistrer le signalement</button></p></form>');
  $('#caseForm').onsubmit = async e => {
    e.preventDefault();
    return commit(async () => {
      if (!db.contracts.length) return toast('Créez un contrat au préalable.');
      let x = Object.fromEntries(new FormData(e.target));
      x.id = 'LT-' + crypto.randomUUID();
      x.stage = 0;
      x.amount = Number(x.amount);
      x.history = [new Date().toLocaleString('fr-FR') + ' · Signalement enregistré'];
      db.cases.push(x);
      await save();
      $('#modal').close();
      render();
      toast('Litige créé.');
    });
  };
}
function caseDetail(id) {
  let x = db.cases.find(x => x.id === id);
  open('<h2>' + esc(x.title) + '</h2><span class="badge blue">' + stages[x.stage] + '</span><p>' + esc(x.description) + '</p><p>Responsable : <b>' + esc(x.owner) + '</b> · Montant : ' + money(x.amount) + '</p><h3>Historique du dossier</h3><div class="timeline">' + x.history.map(h => '<article>' + esc(h) + '</article>').join('') + '</div>' + (x.stage < 3 ? '<label>Note de traitement ' + (x.stage === 2 ? 'et résolution *' : '(facultative)') + '</label><textarea id="caseNote" placeholder="Action réalisée, décision ou solution…"></textarea><p><button class="primary" onclick="advance(\'' + id + '\')">' + (x.stage === 2 ? 'Clôturer le dossier' : 'Passer à : ' + stages[x.stage + 1]) + '</button></p>' : '<div class="notice">Dossier clôturé. La résolution figure dans l’historique.</div>'));
}
function advance(id) {
  return commit(async () => {
    let x = db.cases.find(x => x.id === id),
      note = $('#caseNote').value.trim();
    if (x.stage === 2 && !note) return toast('Décrivez la résolution avant de clôturer.');
    x.stage++;
    x.history.push(new Date().toLocaleString('fr-FR') + ' · ' + stages[x.stage] + (note ? ' — ' + note : ''));
    await save();
    $('#modal').close();
    render();
    caseDetail(id);
  });
}
function example() {
  $('#sourceText').value = 'Le présent contrat est conclu pour une durée de douze mois. Il sera renouvelé par tacite reconduction pour une durée identique. Toute opposition au renouvellement doit être notifiée avec un préavis de 30 jours avant la date de fin. Le fournisseur intervient dans un délai de 48 heures. Tout retard donne lieu à une pénalité forfaitaire de 50 000 Ar. En cas de litige, les parties recherchent une solution amiable.';
}
async function loadText(el) {
  if (el.files[0]) {
    if (el.files[0].size > 1000000) return toast('Fichier trop volumineux (1 Mo maximum).');
    $('#sourceText').value = await el.files[0].text();
  }
}
let ocrBusy = false;
async function runOcr(id) {
  if (ocrBusy || saving) return toast('Un traitement est déjà en cours.');
  ocrBusy = true; saving = true; toast('Reconnaissance du texte en cours…');
  try {
    const response = await fetch('/api/documents/' + encodeURIComponent(id) + '/ocr', {method:'POST'});
    const result = await response.json();
    if (!response.ok) throw Error(result.error || 'OCR indisponible.');
    await refresh();
    if ($('#modal').open) $('#modal').close();
    go('ai'); $('#aiContract').value = result.document.contractId; $('#sourceText').value = result.text;
    await loadAiDocuments(); toast(result.warning || 'Texte reconnu. Relisez-le avant analyse.');
  } catch(error) {toast(error.message);}
  finally {ocrBusy = false; saving = false;}
}
async function exportFullBackup() {
  try {
    const response = await fetch('/api/backups/full');
    if (!response.ok) {const error=await response.json();throw Error(error.error || 'Export impossible.');}
    download(await response.blob(), 'pacte-complet.zip', 'application/zip');
  } catch(error) {toast(error.message);}
}
async function restoreFullBackup(input) {
  const file = input.files[0]; if (!file) return;
  if (saving) return toast('Un enregistrement est en cours.');
  if (file.size > 50000000) return toast('Archive trop volumineuse (50 Mo maximum).');
  if (!confirm('Remplacer tous les contrats, litiges, analyses et PDF par cette sauvegarde complète ?')) {input.value='';return;}
  saving=true;
  try {
    const response=await fetch('/api/backups/full?revision='+revision,{method:'POST',headers:{'Content-Type':'application/zip'},body:file});
    const result=await response.json();
    if(!response.ok)throw Error(result.error || 'Restauration impossible.');
    db=result.data;revision=result.revision;render();toast('Sauvegarde complète restaurée, PDF inclus.');
  } catch(error) {toast(error.message);}
  finally {saving=false;input.value='';}
}
async function loadServiceStatus() {
  try {
    const [mail,ocr,preview]=await Promise.all(['/api/reminders/status','/api/ocr/status','/api/reminders/preview'].map(async path=>{
      const response=await fetch(path);if(!response.ok)throw Error('État des services indisponible.');return response.json();
    }));
    if(page!=='settings')return;
    $('#serviceStatus').innerHTML='<p>OCR : <b>'+(ocr.available?'disponible':'Tesseract non installé ou indisponible')+'</b></p>'+
      '<p>Rappels par e-mail : <b>'+(mail.enabled?(mail.configured?'activés':'configuration incomplète'):'désactivés')+'</b></p>'+
      (mail.lastError?'<p class="notice warn">'+esc(mail.lastError)+'</p>':'')+
      '<h3>Aperçu des prochaines notifications</h3>'+(preview.length?preview.map(r=>'<p><b>'+esc(r.subject)+'</b><br><small>'+esc(r.date)+'</small></p>').join(''):'<p>Aucune échéance dans les 30 jours.</p>')+
      '<p class="muted">Aucun e-mail n’est envoyé depuis cet aperçu. Le serveur vérifie les rappels chaque heure lorsqu’ils sont activés.</p>'+
      '<h3>Derniers rappels envoyés</h3>'+(mail.history.length?mail.history.map(r=>'<p>'+esc(r.contract_id)+' · '+esc(r.reference_date)+' · J−'+esc(r.milestone)+'</p>').join(''):'<p class="muted">Aucun envoi enregistré.</p>');
  }catch(error){if(page==='settings'&&$('#serviceStatus'))$('#serviceStatus').textContent=error.message;}
}

let uploadingPdf = false;

function documentLinks(documents) {
  return documents.length ? documents.map(d => '<article class="notice"><b>' + esc(d.filename) + '</b><br><small>' +
    d.pages + ' page(s) · ' + Math.ceil(d.size / 1024) + ' Ko · ' + d.characters + ' caractères</small><div class="row">' +
    '<a href="/api/documents/' + encodeURIComponent(d.id) + '">Télécharger le PDF</a>' +
    (d.characters ? '<button class="link" onclick="usePdfText(\'' + d.id + '\')">Utiliser le texte pour l’IA</button>' : '<button class="link" onclick="runOcr(\'' + d.id + '\')">Reconnaître le texte (OCR)</button>') + '</div></article>').join('') : '<p class="muted">Aucun PDF associé à ce contrat.</p>';
}
async function loadAiDocuments() {
  if (page !== 'ai' || !$('#aiContract')) return;
  const id = $('#aiContract').value;
  if (!id) { $('#aiDocuments').textContent = 'Créez un contrat pour y associer un PDF.'; return; }
  try {
    const response = await fetch('/api/contracts/' + encodeURIComponent(id) + '/documents');
    const result = await response.json();
    if (!response.ok) throw Error(result.error || 'Documents indisponibles.');
    if (page === 'ai' && $('#aiContract').value === id) $('#aiDocuments').innerHTML = documentLinks(result);
  } catch (error) {
    if (page === 'ai' && $('#aiDocuments')) $('#aiDocuments').textContent = error.message;
  }
}
async function loadContractDocuments(id) {
  try {
    const response = await fetch('/api/contracts/' + encodeURIComponent(id) + '/documents');
    const result = await response.json();
    if (!response.ok) throw Error(result.error || 'Documents indisponibles.');
    if ($('#contractDocuments')?.dataset.contract === id) $('#contractDocuments').innerHTML = documentLinks(result);
  } catch (error) {
    if ($('#contractDocuments')?.dataset.contract === id) $('#contractDocuments').textContent = error.message;
  }
}
async function uploadPdf(input, contractId) {
  const file = input.files[0];
  if (!file) return;
  if (uploadingPdf || saving) { input.value = ''; return toast('Un enregistrement est déjà en cours.'); }
  const fromAnalysis = !contractId;
  const id = contractId || $('#aiContract').value;
  if (!id) return toast('Créez ou sélectionnez un contrat.');
  if (!file.name.toLowerCase().endsWith('.pdf')) return toast('Choisissez un fichier PDF.');
  if (file.size > 10000000) return toast('PDF trop volumineux : 10 Mo maximum.');
  uploadingPdf = true;
  saving = true;
  input.disabled = true;
  toast('Import et extraction du PDF en cours…');
  try {
    const response = await fetch('/api/contracts/' + encodeURIComponent(id) + '/documents?filename=' + encodeURIComponent(file.name), {
      method: 'POST', headers: {'Content-Type': 'application/pdf'}, body: file
    });
    const result = await response.json();
    if (!response.ok) throw Error(result.error || 'Import PDF impossible.');
    await refresh();
    if (fromAnalysis && page === 'ai' && $('#aiContract').value === id) $('#sourceText').value = result.text;
    await loadContractDocuments(id);
    if (page === 'ai') await loadAiDocuments();
    toast(result.warning || 'PDF enregistré. Le texte peut maintenant être analysé.');
  } catch (error) { toast(error.message); }
  finally { uploadingPdf = false; saving = false; input.disabled = false; input.value = ''; }
}
async function usePdfText(id) {
  try {
    const response = await fetch('/api/documents/' + encodeURIComponent(id) + '/text');
    const result = await response.json();
    if (!response.ok) throw Error(result.error || 'Texte du PDF indisponible.');
    await refresh();
    if ($('#modal').open) $('#modal').close();
    go('ai');
    $('#aiContract').value = result.document.contractId;
    $('#sourceText').value = result.text;
    await loadAiDocuments();
    toast(!result.text ? 'Ce PDF nécessite un OCR avant analyse.' : result.text.length > 40000 ? 'Sélectionnez un passage de 40 000 caractères maximum avant l’analyse.' : 'Texte chargé depuis le PDF.');
  } catch (error) { toast(error.message); }
}

let currentAnalysis = null;
let aiRequest = null;
let analyzing = false;
let aiAvailable = false;

async function loadAiStatus() {
  aiAvailable = false;
  try {
    const response = await fetch('/api/ai/status');
    if (!response.ok) throw Error('Impossible de vérifier la disponibilité de l’IA.');
    const status = await response.json();
    aiAvailable = status.configured;
    if (page !== 'ai') return;
    $('#aiStatus').textContent = status.configured
      ? 'IA configurée · ' + status.provider + ' · ' + status.model
      : 'IA non configurée : activez Ollama en local ou renseignez une clé API sur le serveur, puis redémarrez l’application.';
  } catch (error) {
    if (page === 'ai' && $('#aiStatus')) $('#aiStatus').textContent = error.message;
  }
  if (page === 'ai' && $('#analyzeButton')) $('#analyzeButton').disabled = !aiAvailable || analyzing || !db.contracts.length;
}

async function analyze() {
  if (analyzing) return;
  const text = $('#sourceText').value.trim();
  const contractId = $('#aiContract').value;
  if (!text) return toast('Ajoutez le texte du contrat.');
  if (text.length > 40000) return toast('Texte trop long : 40 000 caractères maximum par analyse.');
  if (!contractId) return toast('Créez ou sélectionnez un contrat.');
  if (!aiAvailable) return toast('Configurez la clé API sur le serveur pour activer l’IA.');
  analyzing = true;
  currentAnalysis = null;
  aiRequest = new AbortController();
  const request = aiRequest;
  const timeout = setTimeout(() => request.abort(), 90000);
  $('#analyzeButton').disabled = true;
  $('#aiResult').setAttribute('aria-busy', 'true');
  $('#aiResult').innerHTML = '<h2>Analyse en cours…</h2><p class="muted">Lecture du contrat, repérage des clauses et examen des points à vérifier.</p>';
  try {
    const response = await fetch('/api/ai/analyze', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({contractId, text}),
      signal: request.signal
    });
    const result = await response.json();
    if (!response.ok) throw Error(result.error || 'Analyse IA indisponible.');
    currentAnalysis = result;
    if (page === 'ai') analysisView();
  } catch (error) {
    if (page === 'ai') {
      const message = error.name === 'AbortError' ? 'Analyse interrompue ou délai dépassé. Réessayez.' : error.message;
      $('#aiResult').innerHTML = '<h2>Analyse indisponible</h2><p class="notice warn">' + esc(message) + '</p>';
      toast(message);
    }
  } finally {
    clearTimeout(timeout);
    analyzing = false;
    if (aiRequest === request) aiRequest = null;
    if (page === 'ai' && $('#analyzeButton')) {
      $('#analyzeButton').disabled = !aiAvailable || !db.contracts.length;
      $('#aiResult').removeAttribute('aria-busy');
    }
  }
}

function analysisContent(a) {
  const riskClass = value => ({'Bas': '', 'Modéré': 'orange', 'Élevé': 'red', 'Indéterminé': 'blue'}[value] || 'blue');
  let details = '';
  if (a.ai) {
    details = '<h3>Synthèse du contrat</h3><p>' + esc(a.ai.summary) + '</p>' +
      '<p>Niveau de vigilance : <span class="badge ' + riskClass(a.ai.riskLevel) + '">' + esc(a.ai.riskLevel) + '</span></p>' +
      '<h3>Risques et points à vérifier</h3>' +
      (a.ai.risks.length ? a.ai.risks.map(r => '<article class="notice warn"><b>' + esc(r.title) + '</b> ' +
        '<span class="badge ' + riskClass(r.severity) + '">' + esc(r.severity) + '</span><p>' + esc(r.explanation) + '</p>' +
        (r.excerpt ? '<blockquote>' + esc(r.excerpt) + '</blockquote>' : '<small>Point à vérifier sans extrait associé.</small>') + '</article>').join('') : '<p class="muted">Aucun risque particulier signalé par le modèle ; une vérification reste nécessaire.</p>') +
      '<h3>Actions recommandées</h3>' + (a.ai.recommendations.length ? '<ul>' + a.ai.recommendations.map(r => '<li>' + esc(r) + '</li>').join('') + '</ul>' : '<p class="muted">Aucune action particulière proposée.</p>');
  }
  return '<span class="eyebrow">FICHE D’ANALYSE · ' + (a.ai ? 'IA' : 'REPÉRAGE LOCAL ARCHIVÉ') + '</span><h2>' + esc(a.contract) +
    '</h2><p class="muted">' + esc(a.date) + (a.ai ? ' · ' + esc(a.ai.provider) + ' · ' + esc(a.ai.model) : '') + '</p>' + details +
    '<h3>Clauses et extraits du document</h3>' + a.groups.map(g => '<h3>' + esc(g.label) + '</h3>' +
      (g.excerpts.length ? g.excerpts.map(s => '<blockquote>' + esc(s) + '</blockquote>').join('') : '<p class="muted">Non identifié : vérifier dans le document.</p>')).join('') +
    '<div class="notice ' + (a.validated ? '' : 'warn') + '">' + (a.validated ? '✓ Vérification humaine enregistrée' : 'À vérifier : l’IA peut commettre des erreurs. Cette fiche doit être relue avant validation.') + '</div>';
}
function analysisView() {
  if ($('#aiResult') && currentAnalysis) $('#aiResult').innerHTML = analysisContent(currentAnalysis) + '<button class="primary" onclick="keepAnalysis()">Valider et enregistrer la fiche</button>';
}
function keepAnalysis() {
  if (!currentAnalysis) return toast('Effectuez d’abord une analyse.');
  return commit(async () => {
    const verified = {...structuredClone(currentAnalysis), validated: true};
    db.analysis.unshift(verified);
    await save();
    currentAnalysis = null;
    render();
    toast('Fiche vérifiée et enregistrée.');
  });
}
function showAnalysis(i) {
  open(analysisContent(db.analysis[i]) + '<button onclick="window.print()">Imprimer / enregistrer en PDF</button>');
}
function download(content, name, mime) {
  let a = document.createElement('a'),
    u = URL.createObjectURL(new Blob([content], {
      type: mime
    }));
  a.href = u;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(u), 1000);
}
function csv() {
  let cols = ['id', 'name', 'supplier', 'category', 'amount', 'frequency', 'start', 'end', 'notice', 'renew', 'owner', 'status', 'penalty', 'notes'];
  download('\ufeff' + [cols, ...db.contracts.map(c => cols.map(k => c[k]))].map(r => r.map(v => '"' + (/^[=+@\-\t\r]/.test(String(v ?? '')) ? "'" + v : String(v ?? '')).replace(/"/g, '""') + '"').join(';')).join('\r\n'), 'pacte-contrats.csv', 'text/csv;charset=utf-8');
}
async function restore(el) {
  if (!el.files[0]) return;
  if (el.files[0].size > 2000000) return toast('Sauvegarde trop volumineuse (2 Mo maximum).');
  try {
    const data = JSON.parse(await el.files[0].text());
    if (!confirm('Remplacer les données actuelles ? Les PDF des contrats absents de la sauvegarde seront supprimés.')) return;
    await commit(async () => {
      db = data;
      await save();
      render();
      toast('Sauvegarde restaurée.');
    });
  } catch {
    toast('Sauvegarde JSON invalide.');
  }
}
function reset() {
  if (confirm('Remplacer vos données par les exemples de démonstration ?')) return commit(async () => {
    const response = await fetch('/api/demo');
    db = await response.json();
    await save();
    render();
    toast('Données de démonstration rétablies.');
  });
}
$('#modal').addEventListener('click', e => {
  if (e.target === $('#modal')) {
    let r = $('#modal').getBoundingClientRect();
    if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) $('#modal').close();
  }
});
refresh().then(render).catch(error => {
  $('#view').textContent = error.message;
});
setInterval(() => {
  if (db && !saving && !$('#modal').open && ['dashboard', 'alerts'].includes(page)) refresh().then(render).catch(() => toast('Serveur indisponible.'));
}, 60000);
