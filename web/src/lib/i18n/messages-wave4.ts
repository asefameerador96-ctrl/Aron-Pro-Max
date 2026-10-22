// Strings of the wave-4 admin pages (owner directive 2026-10-22, docs/34): portal levels, module grants, brand scope, route
// monthly targets, house de-duplication, SKU pack fields. Each key holds [English, Bangla] so the catalogues cannot drift;
// messages-en.ts and messages-bn.ts spread the two halves (tests/i18n.test.ts checks parity).
const pairs = {
  // --- portal levels (docs/34 s1) ---
  "role.NATIONAL": ["National user (read)", "জাতীয় ব্যবহারকারী (শুধু দেখা)"],
  "role.BRAND": ["Brand user (own brands)", "ব্র্যান্ড ব্যবহারকারী (নিজের ব্র্যান্ড)"],
  "role.opt.SR": ["Sales representative (app only)", "সেলস রিপ্রেজেন্টেটিভ (শুধু অ্যাপ)"],
  "role.opt.AMO": ["Area marketing officer (app only)", "এরিয়া মার্কেটিং অফিসার (শুধু অ্যাপ)"],
  "role.app_only": ["{role} (app only, no portal login)", "{role} (শুধু অ্যাপ, পোর্টালে লগইন নেই)"],
  "role.group.portal": ["Portal levels", "পোর্টাল স্তর"],
  "role.group.app": ["Field app only", "শুধু ফিল্ড অ্যাপ"],
  "role.group.service": ["Internal service roles", "অভ্যন্তরীণ সেবা ভূমিকা"],
  "role.app_only_note": ["SR and AMO work in the field apps only: they have no portal login, no module grants and no brand scope.", "এসআর ও এএমও শুধু ফিল্ড অ্যাপে কাজ করেন: তাদের পোর্টাল লগইন, মডিউল অনুমতি ও ব্র্যান্ড সীমা নেই।"],
  // --- user access page ---
  "action.user_access": ["Access", "প্রবেশাধিকার"],
  "access.title": ["Access of a user", "ব্যবহারকারীর প্রবেশাধিকার"],
  "access.modules.title": ["Module grants", "মডিউল অনুমতি"],
  "access.modules.intro": ["An Administrator reads or writes only the modules ticked here. A module with no grant is not listed in the menu and its API answers 403.", "অ্যাডমিন শুধু এখানে বাছাই করা মডিউল দেখতে বা লিখতে পারেন। অনুমতি ছাড়া মডিউল মেনুতে আসে না এবং এপিআই ৪০৩ দেয়।"],
  "access.module": ["Module", "মডিউল"],
  "access.level.none": ["No access", "প্রবেশাধিকার নেই"],
  "access.level.r": ["Read", "দেখা"],
  "access.level.w": ["Read and write", "দেখা ও লেখা"],
  "access.brands.title": ["Brand scope", "ব্র্যান্ড সীমা"],
  "access.brands.intro": ["A brand user sees only the products, cards and reports of the brands ticked here, nationwide, read only.", "ব্র্যান্ড ব্যবহারকারী শুধু এখানে বাছাই করা ব্র্যান্ডের পণ্য, কার্ড ও রিপোর্ট দেখেন, সারা দেশে, শুধু দেখার জন্য।"],
  "access.brands.empty": ["No brands are defined yet.", "এখনো কোনো ব্র্যান্ড নেই।"],
  "access.brands.none_selected": ["Tick at least one brand: a brand user without a brand would see nothing.", "অন্তত একটি ব্র্যান্ড বাছাই করুন: ব্র্যান্ড ছাড়া ব্যবহারকারী কিছুই দেখবেন না।"],
  "access.none.title": ["No grants for this role", "এই ভূমিকার জন্য অনুমতি নেই"],
  "access.none.body": ["Module grants exist for Administrators and brand scope for Brand users. Other roles are fixed by their level.", "মডিউল অনুমতি অ্যাডমিনদের এবং ব্র্যান্ড সীমা ব্র্যান্ড ব্যবহারকারীদের জন্য। অন্য ভূমিকা স্তর অনুযায়ী নির্দিষ্ট।"],
  "access.super_only": ["Only a Super Administrator changes roles, grants and brand scope.", "শুধু সুপার অ্যাডমিন ভূমিকা, অনুমতি ও ব্র্যান্ড সীমা বদলাতে পারেন।"],
  "access.saved": ["Access saved. The user's next token carries the new grants.", "প্রবেশাধিকার সংরক্ষিত। ব্যবহারকারীর পরের টোকেনে নতুন অনুমতি থাকবে।"],
  "access.stale": ["Someone changed this user's access meanwhile. Reload and try again.", "ইতিমধ্যে কেউ এই ব্যবহারকারীর প্রবেশাধিকার বদলেছেন। পুনরায় লোড করে আবার চেষ্টা করুন।"],
  "access.load_failed": ["The grants could not be loaded.", "অনুমতি লোড করা যায়নি।"],
  "grant.module.masterdata": ["Master data", "মাস্টার ডেটা"],
  "grant.module.routes": ["Routes and assignment", "রুট ও দায়িত্ব"],
  "grant.module.outlets": ["Outlets", "আউটলেট"],
  "grant.module.products": ["Products and SKUs", "পণ্য ও এসকেইউ"],
  "grant.module.prices": ["Prices", "মূল্য"],
  "grant.module.users": ["Users and scope", "ব্যবহারকারী ও পরিধি"],
  "grant.module.targets": ["Targets and calendar", "টার্গেট ও ক্যালেন্ডার"],
  "grant.module.credentials": ["Credentials", "পাসওয়ার্ড ও লগইন"],
  "grant.module.mdm": ["MDM", "এমডিএম"],
  "grant.module.reports": ["Reports and exports", "রিপোর্ট ও এক্সপোর্ট"],
  "grant.module.config": ["Configuration", "কনফিগারেশন"],
} as const satisfies Record<string, readonly [string, string]>;

export type Wave4MessageKey = keyof typeof pairs;

function half(i: 0 | 1): Record<Wave4MessageKey, string> {
  return Object.fromEntries(Object.entries(pairs).map(([k, v]) => [k, v[i]])) as Record<Wave4MessageKey, string>;
}

export const w4En = half(0);
export const w4Bn = half(1);
