// Strings of the TSO manual build on the web (lane W1, build contract docs/requests/tso-manual-build-2026-10.md s5 W1): the support
// console (D-TSO-MAN-18 to 25), the ticket age (D-TSO-MAN-25) and the support module grant (R-F-11). Each key holds [English,
// Bangla] so the catalogues cannot drift; messages-en.ts and messages-bn.ts spread the two halves (tests/i18n.test.ts checks
// parity). Keys sit under `tickets.` and `tsoman.`; `grant.module.support` (R-F-11) and the two `error.` problem texts (the
// key form problemMessage reads) are the only others.
const pairs = {
  // --- R-F-11: the ADMIN module of the support desk routes ---
  "grant.module.support": ["Support tickets", "সাপোর্ট টিকিট"],
  // --- problem codes of contract 1.10.0 (the console shows them after a refused action) ---
  "error.ERR_TICKET_TRANSITION_INVALID": ["This change is not allowed in the ticket's current status. Reload the ticket to see it.", "টিকিটের বর্তমান অবস্থায় এই পরিবর্তন করা যায় না। টিকিটটি রিলোড করে দেখুন।"],
  "error.ERR_TICKET_COMMENTS_CLOSED": ["This ticket is no longer open. No further comments can be added.", "এই টিকিটটি আর খোলা নেই। আর কোনো মন্তব্য যোগ করা যাবে না।"],

  // --- menu and page ---
  "tickets.menu": ["Tickets", "টিকিট"],
  "tickets.title": ["Support tickets", "সাপোর্ট টিকিট"],
  "tickets.intro": ["Tickets raised from the TSO app. Reply, start, resolve, cancel or close them; the reporter sees every change in the app.", "টিএসও অ্যাপ থেকে তোলা টিকিট। উত্তর দিন, কাজ শুরু করুন, সমাধান, বাতিল বা বন্ধ করুন; রিপোর্টকারী অ্যাপে প্রতিটি পরিবর্তন দেখেন।"],
  "tickets.no_desk": ["Only the support desk works tickets. Ask a Super Administrator for the support permission.", "শুধু সাপোর্ট ডেস্ক টিকিট নিয়ে কাজ করে। সাপোর্ট অনুমতির জন্য সুপার অ্যাডমিনকে বলুন।"],
  "tickets.as_of": ["Counts as of {time}", "{time} পর্যন্ত হিসাব"],
  "tickets.category": ["Tickets", "টিকিট"],

  // --- statuses and priorities (labels as the app shows them, D-TSO-MAN-19) ---
  "tickets.status.new": ["New", "নতুন"],
  "tickets.status.in_progress": ["In Progress", "চলমান"],
  "tickets.status.resolved": ["Resolved", "সমাধান হয়েছে"],
  "tickets.status.closed": ["Closed", "বন্ধ"],
  "tickets.status.cancelled": ["Cancelled", "বাতিল"],
  "tickets.tab.all": ["All", "সব"],
  "tickets.tabs": ["Ticket status", "টিকিটের অবস্থা"],
  "tickets.priority.p1": ["P1 - Highest", "P1 - সর্বোচ্চ"],
  "tickets.priority.p2": ["P2 - High", "P2 - উচ্চ"],
  "tickets.priority.p3": ["P3 - Medium", "P3 - মাঝারি"],
  "tickets.priority.p4": ["P4 - Low", "P4 - নিম্ন"],

  // --- summary (screenshot 11) ---
  "tickets.summary.total": ["Total", "মোট"],
  "tickets.summary.open": ["Open", "খোলা"],
  "tickets.summary.resolution": ["Ticket Resolution Status", "টিকিট সমাধানের অবস্থা"],
  "tickets.summary.resolved_of": ["{n} of {total} tickets resolved", "{total}টি টিকিটের মধ্যে {n}টি সমাধান হয়েছে"],
  "tickets.summary.open_of": ["{n} of {total} tickets open", "{total}টি টিকিটের মধ্যে {n}টি খোলা"],
  "tickets.summary.failed": ["The counts could not be loaded.", "হিসাব লোড করা যায়নি।"],

  // --- list ---
  "tickets.search": ["Search", "খুঁজুন"],
  "tickets.search.hint": ["Ticket number (INC…) or words of the title or issue type, 2 to 40 characters.", "টিকিট নম্বর (INC…) বা শিরোনাম বা সমস্যার ধরনের শব্দ, ২ থেকে ৪০ অক্ষর।"],
  "tickets.search.too_short": ["Type at least 2 characters to search; the list below is not filtered by text.", "খুঁজতে অন্তত ২টি অক্ষর লিখুন; নিচের তালিকা লেখা দিয়ে ছাঁকা হয়নি।"],
  "tickets.filter.priority": ["Priority", "অগ্রাধিকার"],
  "tickets.filter.issue": ["Issue type", "সমস্যার ধরন"],
  "tickets.filter.from": ["From", "থেকে"],
  "tickets.filter.to": ["To", "পর্যন্ত"],
  "tickets.filter.any": ["Any", "যেকোনো"],
  "tickets.filter.apply": ["Apply", "প্রয়োগ করুন"],
  "tickets.filter.clear": ["Clear", "মুছুন"],
  "tickets.col.ticket": ["Ticket", "টিকিট"],
  "tickets.col.title": ["Title", "শিরোনাম"],
  "tickets.col.requester": ["Raised by", "যিনি তুলেছেন"],
  "tickets.col.priority": ["Priority", "অগ্রাধিকার"],
  "tickets.col.status": ["Status", "অবস্থা"],
  "tickets.col.age": ["Raised", "তোলা হয়েছে"],
  "tickets.col.updated": ["Updated", "হালনাগাদ"],
  "tickets.list": ["Tickets", "টিকিটের তালিকা"],
  "tickets.empty": ["No ticket matches.", "কোনো টিকিট মেলেনি।"],
  "tickets.next": ["Next page", "পরের পাতা"],
  "tickets.first": ["First page", "প্রথম পাতা"],
  "tickets.unnumbered": ["Not numbered yet", "এখনো নম্বর হয়নি"],
  "tickets.age.just_now": ["just now", "এইমাত্র"],
  "tickets.age.m": ["{n}m ago", "{n} মিনিট আগে"],
  "tickets.age.h": ["{n}h ago", "{n} ঘণ্টা আগে"],
  "tickets.age.d": ["{n}d ago", "{n} দিন আগে"],

  // --- detail ---
  "tickets.back": ["All tickets", "সব টিকিট"],
  "tickets.facts": ["Ticket", "টিকিটের তথ্য"],
  "tickets.field.issue": ["Issue type", "সমস্যার ধরন"],
  "tickets.field.requester": ["Raised by", "যিনি তুলেছেন"],
  "tickets.field.affected": ["Affected user", "যার সমস্যা"],
  "tickets.field.source": ["Raised with", "কীভাবে তোলা"],
  "tickets.source.quick": ["Quick Ticket", "কুইক টিকিট"],
  "tickets.source.manual": ["Create Ticket", "ক্রিয়েট টিকিট"],
  "tickets.field.business_date": ["Business date", "ব্যবসার তারিখ"],
  "tickets.field.captured": ["Raised on the phone", "ফোনে তোলার সময়"],
  "tickets.field.first_response": ["First support response", "সাপোর্টের প্রথম উত্তর"],
  "tickets.field.resolved_at": ["Resolved", "সমাধানের সময়"],
  "tickets.field.closed_at": ["Closed", "বন্ধের সময়"],
  "tickets.field.cancelled_at": ["Cancelled", "বাতিলের সময়"],
  "tickets.field.resolution": ["Resolution", "সমাধান"],
  "tickets.field.cancel_reason": ["Reason for cancelling", "বাতিলের কারণ"],
  "tickets.field.photos": ["Photos", "ছবি"],
  "tickets.field.assignee": ["Assigned to", "দায়িত্বপ্রাপ্ত"],
  "tickets.field.description": ["Description", "বিবরণ"],
  "tickets.no_description": ["No description.", "কোনো বিবরণ নেই।"],
  "tickets.thread": ["Conversation", "কথোপকথন"],
  "tickets.thread.empty": ["No messages yet.", "এখনো কোনো বার্তা নেই।"],
  "tickets.support": ["Support", "সাপোর্ট"],
  "tickets.event.created": ["Raised the ticket", "টিকিট তুলেছেন"],
  "tickets.event.close_review": ["Submitted the review and closed the ticket", "রিভিউ দিয়ে টিকিট বন্ধ করেছেন"],
  "tickets.event.status_change": ["Status: {from} → {to}", "অবস্থা: {from} → {to}"],
  "tickets.event.priority_change": ["Priority changed", "অগ্রাধিকার বদলানো হয়েছে"],
  "tickets.event.assign": ["Assignment changed", "দায়িত্ব বদলানো হয়েছে"],
  "tickets.event.photos": ["{n} photo(s)", "{n}টি ছবি"],
  "tickets.closed.resolved": ["This ticket is resolved. No further comments can be added.", "এই টিকিটটি সমাধান হয়েছে। আর কোনো মন্তব্য যোগ করা যাবে না।"],
  "tickets.closed.closed": ["This ticket is closed. No further comments can be added.", "এই টিকিটটি বন্ধ। আর কোনো মন্তব্য যোগ করা যাবে না।"],
  "tickets.closed.cancelled": ["This ticket is cancelled. No further comments can be added.", "এই টিকিটটি বাতিল। আর কোনো মন্তব্য যোগ করা যাবে না।"],

  // --- desk actions (D-TSO-MAN-20, 21) ---
  "tickets.desk": ["Support desk", "সাপোর্ট ডেস্ক"],
  "tickets.desk.read_only": ["Your grant on support tickets is read only: you can read tickets but not change them.", "সাপোর্ট টিকিটে আপনার অনুমতি শুধু দেখার: আপনি টিকিট পড়তে পারবেন, বদলাতে পারবেন না।"],
  "tickets.desk.none": ["No action is open for this ticket.", "এই টিকিটে কোনো কাজ বাকি নেই।"],
  "tickets.reply.label": ["Reply to the reporter", "রিপোর্টকারীকে উত্তর"],
  "tickets.reply.hint": ["1 to 2,000 characters. The reporter sees it as Support. The first reply on a New ticket moves it to In Progress.", "১ থেকে ২,০০০ অক্ষর। রিপোর্টকারী এটি সাপোর্ট হিসেবে দেখেন। নতুন টিকিটে প্রথম উত্তর দিলে তা চলমান হয়।"],
  "tickets.reply.submit": ["Send reply", "উত্তর পাঠান"],
  "tickets.reply.done": ["Reply sent.", "উত্তর পাঠানো হয়েছে।"],
  "tickets.start.submit": ["Start work (In Progress)", "কাজ শুরু করুন (চলমান)"],
  "tickets.start.done": ["The ticket is In Progress.", "টিকিটটি এখন চলমান।"],
  "tickets.resolve.title": ["Resolve", "সমাধান"],
  "tickets.resolve.code": ["Resolution code", "সমাধান কোড"],
  "tickets.resolve.notes": ["Resolution notes", "সমাধানের নোট"],
  "tickets.resolve.hint": ["1 to 2,000 characters. The reporter gets \"[TicketID: …] - Resolved\" with the code and these notes, then closes the ticket.", "১ থেকে ২,০০০ অক্ষর। রিপোর্টকারী কোড ও এই নোটসহ \"[TicketID: …] - Resolved\" বার্তা পান, তারপর টিকিট বন্ধ করেন।"],
  "tickets.resolve.choose": ["Choose a code", "একটি কোড বাছুন"],
  "tickets.resolve.submit": ["Resolve ticket", "টিকিট সমাধান করুন"],
  "tickets.resolve.done": ["The ticket is resolved.", "টিকিটটি সমাধান হয়েছে।"],
  "tickets.resolve.codes_fallback": ["The resolution code list could not be loaded; only the standard code is offered.", "সমাধান কোডের তালিকা লোড হয়নি; শুধু সাধারণ কোডটি দেওয়া হলো।"],
  "tickets.cancel.title": ["Cancel", "বাতিল"],
  "tickets.cancel.reason": ["Reason for cancelling", "বাতিলের কারণ"],
  "tickets.cancel.hint": ["3 to 500 characters, for example a duplicate or a test ticket. The reporter sees the ticket as Cancelled.", "৩ থেকে ৫০০ অক্ষর, যেমন একই টিকিট দুবার বা পরীক্ষার টিকিট। রিপোর্টকারী টিকিটটি বাতিল দেখেন।"],
  "tickets.cancel.submit": ["Cancel ticket", "টিকিট বাতিল করুন"],
  "tickets.cancel.confirm": ["Cancel this ticket? It cannot be opened again.", "এই টিকিট বাতিল করবেন? এটি আর খোলা যাবে না।"],
  "tickets.cancel.done": ["The ticket is cancelled.", "টিকিটটি বাতিল হয়েছে।"],
  "tickets.close.hint": ["The reporter normally closes a resolved ticket with Submit Review & Close. Close it here only when they cannot.", "রিপোর্টকারী সাধারণত Submit Review & Close দিয়ে সমাধান হওয়া টিকিট বন্ধ করেন। তারা না পারলে তবেই এখানে বন্ধ করুন।"],
  "tickets.close.submit": ["Close ticket", "টিকিট বন্ধ করুন"],
  "tickets.close.done": ["The ticket is closed.", "টিকিটটি বন্ধ হয়েছে।"],
  "tickets.priority.label": ["Priority", "অগ্রাধিকার"],
  "tickets.priority.submit": ["Change priority", "অগ্রাধিকার বদলান"],
  "tickets.priority.done": ["The priority is changed.", "অগ্রাধিকার বদলানো হয়েছে।"],
  "tickets.priority.same": ["Choose a different priority.", "অন্য একটি অগ্রাধিকার বাছুন।"],
  "tickets.conflict": ["Someone changed this ticket after you opened it. Reload to see the latest version, then apply your change again.", "আপনি খোলার পরে কেউ এই টিকিটটি বদলেছেন। সর্বশেষ অবস্থা দেখতে রিলোড করুন, তারপর আবার আপনার পরিবর্তন দিন।"],
  "tickets.reload": ["Reload", "রিলোড করুন"],
  "tickets.busy": ["Sending…", "পাঠানো হচ্ছে…"],

  // --- step 2. DASH-13: Login & Bikroy Joma drill (D-TSO-MAN-12; tab names as screenshot 8) ---
  "tsoman.drill.not_logged_in": ["Not Logged In", "লগইন করেননি"],
  "tsoman.drill.not_uploaded": ["Not Uploaded", "আপলোড করেননি"],
  "tsoman.drill.col.username": ["User Name", "ইউজার নেম"],
  "tsoman.drill.col.dep_name": ["Dep Name", "ডেপ নেম"],
  "tsoman.drill.col.role": ["Role", "ভূমিকা"],
  "tsoman.drill.routes_not_logged_in": ["{n} routes without a login today.", "আজ {n}টি রুটে লগইন হয়নি।"],
  "tsoman.drill.routes_not_uploaded": ["{n} routes logged in without Bikroy Joma.", "{n}টি রুটে লগইন হয়েছে, বিক্রয় জমা হয়নি।"],
  "tsoman.drill.truncated": ["The list is cut at {shown} rows. Narrow the filter (a territory or a zone) to see every row.", "তালিকাটি {shown} সারিতে কাটা হয়েছে। সব সারি দেখতে ফিল্টার ছোট করুন (একটি টেরিটরি বা জোন)।"],
  "tsoman.drill.login_note": ["One row per route for its SR (SR Not Set when the route has none), then one row per AMO covering the route's zone. Dep Name is the zone.", "প্রতিটি রুটের জন্য তার এসআর-এর একটি সারি (এসআর না থাকলে এসআর নির্ধারিত নয়), তারপর রুটের জোন দেখেন এমন প্রতিটি এএমও-র একটি সারি। ডেপ নেম হলো জোন।"],
  "tsoman.role.SR": ["SR", "এসআর"],
  "tsoman.role.AMO": ["AMO", "এএমও"],

  // --- step 2. FS-14: reopen a zone-day (D-TSO-MAN-29) ---
  "tsoman.reopen.window_hint": ["Administrators only, and only within the reopen window after the business date (a setting on the server). The zone's figures are rebuilt and the zone must be final-submitted again.", "শুধু অ্যাডমিন, এবং শুধু ব্যবসার তারিখের পরে পুনরায় খোলার সময়সীমার মধ্যে (সার্ভারের একটি সেটিং)। জোনের হিসাব নতুন করে তৈরি হয় এবং জোনটি আবার ফাইনাল সাবমিট করতে হয়।"],
  "tsoman.reopen.reason_hint": ["10 to 500 characters, kept in the audit log.", "১০ থেকে ৫০০ অক্ষর, অডিট লগে থাকে।"],
  "tsoman.reopen.reason_short": ["At least 10 characters.", "কমপক্ষে ১০ অক্ষর।"],
  "tsoman.reopen.future": ["A future day cannot be reopened.", "ভবিষ্যতের দিন খোলা যায় না।"],
  "tsoman.reopen.window": ["This day is outside the reopen window.", "এই দিনটি পুনরায় খোলার সময়সীমার বাইরে।"],
  "tsoman.reopen.confirm": ["Reopen zone {zone} for {date}? Its figures are rebuilt and the zone must be final-submitted again.", "{date} তারিখের জোন {zone} পুনরায় খুলবেন? হিসাব নতুন করে তৈরি হবে এবং জোনটি আবার ফাইনাল সাবমিট করতে হবে।"],
  "tsoman.reopen.window_n": ["This day is outside the reopen window of {n} days.", "এই দিনটি {n} দিনের পুনরায় খোলার সময়সীমার বাইরে।"],
  "tsoman.reopen.done": ["Zone {zone} for {date} is reopened ({at}). The action is in the audit log.", "{date} তারিখের জোন {zone} পুনরায় খোলা হয়েছে ({at})। কাজটি অডিট লগে আছে।"],
  "tsoman.reopen.retry": ["No answer from the server. Send again: the same request is repeated, never applied twice.", "সার্ভার থেকে উত্তর আসেনি। আবার পাঠান: একই অনুরোধ আবার যাবে, দুবার কার্যকর হবে না।"],
  "error.ERR_DAY_NOT_FINAL_SUBMITTED": ["This zone-day is not final-submitted, so there is nothing to reopen.", "এই জোন-দিনটি ফাইনাল সাবমিট হয়নি, তাই খোলার কিছু নেই।"],

  // --- step 2. LV-19: the deciding page (applicant, territory, type label, optional reject note) ---
  "tsoman.leave.col.applicant": ["Applicant", "আবেদনকারী"],
  "tsoman.leave.col.territory": ["Territory", "টেরিটরি"],
  "tsoman.leave.note": ["Note to the TSO (optional)", "টিএসও-র জন্য নোট (ঐচ্ছিক)"],
  "tsoman.leave.note_hint": ["Up to {n} characters, kept with the decision.", "সর্বোচ্চ {n} অক্ষর, সিদ্ধান্তের সাথে রাখা হয়।"],
  "tsoman.leave.reject_confirm": ["Reject leave", "ছুটি নাকচ করুন"],
  "tsoman.leave.back": ["Back", "ফিরে যান"],

  // --- step 2. FB-12: the feedback inbox ---
  "tsoman.feedback.photo": ["Photo", "ছবি"],
  "tsoman.feedback.zone": ["Zone", "জোন"],

  // --- step 2. XA-02: the TSO visit log report (R-F-12) ---
  "tsoman.report.tso_visit_log": ["TSO Visit Log", "টিএসও ভিজিট লগ"],

  // --- step 2. quarantine reason after_final_submit (D-TSO-MAN-31) ---
  // --- step 2. TSO Daily Tracking: the till-date achievement per route (B2's DailyTrackingApi, two decimals) ---
  "tsoman.tracking.tilldate": ["Till-date {pct}", "আজ পর্যন্ত {pct}"],

  "tsoman.quarantine.after_final_submit": ["Late sale after Final Submit", "ফাইনাল সাবমিটের পরে আসা বিক্রি"],
  "tsoman.quarantine.accept_late": ["Accept as late sale", "দেরিতে আসা বিক্রি হিসেবে গ্রহণ"],
  "tsoman.quarantine.late_help": ["Captured on the phone before the zone's Final Submit, but it reached the server after it. It is kept out of the day's figures until you decide. Accept as late sale counts it in that day, marked late; Discard keeps it out. Nothing is lost while it waits here.", "জোনের ফাইনাল সাবমিটের আগে ফোনে নেওয়া, কিন্তু সার্ভারে পৌঁছেছে পরে। আপনি সিদ্ধান্ত না দেওয়া পর্যন্ত এটি দিনের হিসাবে ধরা হয় না। দেরিতে আসা বিক্রি হিসেবে গ্রহণ করলে সেই দিনের হিসাবে দেরি চিহ্নসহ যোগ হয়; বাতিল করলে বাদ থাকে। এখানে অপেক্ষার সময় কিছুই হারায় না।"],
} as const satisfies Record<string, readonly [string, string]>;

export type TsoManMessageKey = keyof typeof pairs;

function half(i: 0 | 1): Record<TsoManMessageKey, string> {
  return Object.fromEntries(Object.entries(pairs).map(([k, v]) => [k, v[i]])) as Record<TsoManMessageKey, string>;
}

export const tsomanEn = half(0);
export const tsomanBn = half(1);
