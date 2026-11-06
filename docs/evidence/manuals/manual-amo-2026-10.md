# ARON AMO App User Manual (owner upload, October 2026) — sanitised text

Source: the owner's `ARON_AMO_App_User_Manual_2.docx` and `ARON_AMO_Manual_Screenshots_JPG_1.zip` (uploaded 2026-11-06). Text verbatim; the 77 screenshots are in `docs/ui-reference/amo/` (numbered as in the manual) with one note `NN-*.md` next to each. The vendor app's wire protocol is not part of this material (clean-room rule). Where this text and a screenshot disagree, the screenshot wins (owner directive 14a).

Step-by-step guide with screenshots for the Assistant Marketing Officer (AMO)
Abul Khair Tobacco Co. Ltd.  |  October 2026
# Contents
1	About this manual
2	Installing the app
3	Logging in and permissions
4	Updating the app
5	Dashboard
6	Connecting the mobile printer
7	Attendance (check in / check out)
8	Control Call and selling
9	Joint Call (SR call assessment)
10	Team Location
11	Team Performance
12	Task Delegation
13	Live Dashboard
14	SR Stock
15	Stock
16	Memo: reprint, edit and due collection
17	Sales Summary
18	Outlet
19	Sales Submit (Bikroy Joma)
20	Astha
21	Report
22	Sales Journey
23	Settings and Log Out
24	Quick answers (FAQ)
Annex A	Bangla screen labels in English
# 1. About this manual
This manual explains every screen of the ARON AMO app, in the order an AMO (Assistant Marketing Officer) uses it. Each step shows one screenshot (or two related screens) on the left, and on the right what the screen shows and what to do.
- Who uses the app: the AMO, who supervises the SRs of a zone. The AMO checks in and out, makes his own outlet calls (Control Call), assesses SRs in the market (Joint Call), follows SR location, stock and performance, verifies outlet requests from SRs, and can sell from the app when he covers a route.
- Language: the app screens are in Bangla (English can be selected in Settings). This manual gives the English meaning of every label; Annex A lists the Bangla labels with their English names.
- Common buttons: the back arrow (top left) returns to the previous screen. Most selection screens use a route drop-down, a retailer (outlet) drop-down and an alphabet bar (All, A, B, C ...) that filters the outlet list by the first letter of the name.
- Units: Cigarette and Bidi in sticks, Lighter in pieces, Match in dozens.
- Screenshots: taken from test and live accounts, so names and figures are examples only. Every screenshot is numbered; the text next to it describes everything on the screen.
# 2. Installing the app
The app is installed from the APK file shared by the company (it is not on Play Store).
**Screenshot 1** (`docs/ui-reference/amo/01-find_the_apk_and_install.jpg`)

1. Find the APK and install What you see Left: the File Manager, Internal storage, with the file "aron_amo_app_<date>_v1.apk". Right: the pop-up "ARON AMO - Do you want to install this app?" with Cancel and Install. What to do Open the File Manager and tap the ARON AMO APK file. Tap Install and wait. Note: If the phone blocks the install, allow "Install unknown apps" for the File Manager when asked.
**Screenshot 2** (`docs/ui-reference/amo/02-installation_complete.jpg`)

2. Installation complete What you see Left: "App installed" with Done and Open. Right: the ARON AMO icon on the phone. What to do Tap Open to start the app, or Done to close the installer. Later, open the app from the ARON AMO icon.
# 3. Logging in and permissions
**Screenshot 3** (`docs/ui-reference/amo/03-login_and_location_permission.jpg`)

3. Login and location permission What you see Login screen: Username (ইউজারনেম), Password (পাসওয়ার্ড) and the Login (লগইন) button. The app version is shown below. Location permission: "Allow ARON AMO to access this device's location?" with Precise / Approximate and While using the app, Only this time, Don't allow. What to do Enter your AMO username and password and tap লগইন. Choose Precise and tap "While using the app". The app cannot be used without location permission.
**Screenshot 4** (`docs/ui-reference/amo/04-camera_and_audio_permission.jpg`)

4. Camera and audio permission What you see The first time the camera opens (outlet photo, manual override): "Allow ARON AMO to take pictures and record video?" and then "Allow ARON AMO to record audio?". What to do Tap "While using the app" on both. Without them outlet photos cannot be taken.
# 4. Updating the app
**Screenshot 5** (`docs/ui-reference/amo/05-new_update_available.jpg`)

5. New update available What you see Left: "NEW UPDATE - Update Available - Version x.x.x available" with the button Download App & Install. Network Status (online) is shown at the top. Right: the phone's "Install unknown apps" page, with a switch for ARON AMO. What to do Tap Download App & Install. If the phone opens "Install unknown apps", switch ARON AMO on and go back.
**Screenshot 6** (`docs/ui-reference/amo/06-download_and_install_the_update.jpg`)

6. Download and install the update What you see Left: download progress (e.g. "Downloading 53%"), with the warning "Please do not close the app while downloading". Right: "Do you want to update this app?" with Cancel and Update. What to do Keep the internet on and wait for the download to finish. Tap Update, then Open when it is done.
**Screenshot 7** (`docs/ui-reference/amo/07-update_from_inside_the_app.jpg`)

7. Update from inside the app What you see Left: after login, the message "Some new updates have been found. To keep using this app you must update it" with the আপডেট (Update) button and the current version. Right: "App is updating" (অ্যাপ আপডেট হচ্ছে) with a progress bar. Tip: do not close the app. What to do Tap আপডেট and wait until the progress reaches 100%.
# 5. Dashboard
**Screenshot 8** (`docs/ui-reference/amo/08-dashboard_menu.jpg`)

8. Dashboard menu What you see Top: your name and AMO ID (e.g. "AMO-Dariapur (amo5864)"), your area and today's date. Tiles: Attendance, Joint Call, Control Call, Team Location, Team Performance, Task Delegation, Live Dashboard, SR Stock, Outlet, Stock, Sales, Memo, Summary, Sales Submit (Bikroy Joma), Astha, Report, Sales Journey, Settings. A red badge on the Outlet tile shows the number of outlet requests from SRs waiting for verification. What to do Tap a tile to open it. Scroll down to see Settings and the target cards (next screenshot).
| Tile (Bangla) | English | Use it to |
|---|---|---|
| অ্যাটেনডেন্স | Attendance | Check in at the start and check out at the end of the day (section 7). |
| জয়েন্ট কল | Joint Call | Assess an SR's call at an outlet (section 9). |
| কন্ট্রোল কল | Control Call | Make your own outlet call: sell, assess SR performance, survey (section 8). |
| টিম লোকেশন | Team Location | See the live location of your SRs (section 10). |
| টিম পারফর্মেন্স | Team Performance | Target vs achievement of your zone and routes (section 11). |
| টাস্ক ডেলিগেশন | Task Delegation | Give tasks to SRs for outlets (section 12). |
| লাইভ ড্যাশবোর্ড | Live Dashboard | Live sales, strike rate, geo-fencing, login and Bikroy Joma by route (section 13). |
| এস আর স্টক | SR Stock | Stock lifted by each SR (section 14). |
| আউটলেট | Outlet | Verify SR outlet requests; add, close or change outlets; update base location (section 18). |
| স্টক | Stock | Take the stock issued to you and connect the printer (sections 6 and 15). |
| বিক্রয় | Sales | Sell to an outlet (section 8). |
| মেমো | Memo | Reprint or edit a memo and collect dues (section 16). |
| সারসংক্ষেপ | Summary | Today's sales summary (section 17). |
| বিক্রয় জমা | Sales Submit (Bikroy Joma) | Sync and submit the day's sales (section 19). |
| আস্থা | Astha | Astha outlet targets and achievement (section 20). |
| Report | Report | STD memo report and sales-till-now summary (section 21). |
| বিক্রয় যাত্রা | Sales Journey | See each SR's calls of the day in time order, with call time, walking time and distance (section 22). |
| সেটিং | Settings | Language, send data file to support, log out (section 23). |
**Screenshot 9** (`docs/ui-reference/amo/09-target_cards_bottom_of_the_dashboard.jpg`)

9. Target cards (bottom of the Dashboard) What you see আজকের টার্গেট (Today's Target): target completed today / today's total target. টোটাল কল টার্গেট (Total Call Target): calls completed this month / this month's total call target. কন্ট্রোল কল টার্গেট (Control Call Target): control calls completed this month / control call target for the month. জয়েন্ট কল টার্গেট (Joint Call Target): joint calls completed this month / joint call target for the month. What to do Check the cards during the day to see how many calls are still due.
# 6. Connecting the mobile printer
Memos are printed on a Bluetooth mobile printer. Pair it once with the phone, then connect it in the app from the Stock screen.
**Screenshot 10** (`docs/ui-reference/amo/10-turn_on_bluetooth_and_find_the_printer.jpg`)

10. Turn on Bluetooth and find the printer What you see Left: phone Settings > Bluetooth, switched off. Right: Bluetooth on; the printer (e.g. "RPP02N") appears under Available devices. What to do Switch on the printer (Power) and the phone's Bluetooth. Tap the printer name.
**Screenshot 11** (`docs/ui-reference/amo/11-pair_with_pin_0000.jpg`)

11. Pair with PIN 0000 What you see Left: "Bluetooth pairing request" asking for a PIN. Right: the printer now shows under Paired devices. What to do Enter PIN 0000 (or 1234) and tap Pair.
**Screenshot 12** (`docs/ui-reference/amo/12-allow_bluetooth_in_the_app.jpg`)

12. Allow Bluetooth in the app What you see Left: open the Stock (স্টক) tile on the Dashboard. Right: the app asks "Allow ARON AMO to find, connect to and determine the relative position of nearby devices?". What to do Tap Allow. Without it the printer cannot connect. Tap the printer icon at the top right of the Stock screen.
**Screenshot 13** (`docs/ui-reference/amo/13-printer_connected.jpg`)

13. Printer connected What you see Left: the message "প্রিন্টার কানেক্ট করা হয়েছে" (Printer connected). Right: the printer icon turns green. What to do You can now print memos. A red, crossed-out printer icon means the printer is not connected.
# 7. Attendance (check in / check out)
**Screenshot 14** (`docs/ui-reference/amo/14-attendance_screen.jpg`)

14. Attendance screen What you see Your name and ID, and your current location (address) with a refresh button. Left (before check-in): "You have not checked in yet. Please check in before starting work." with চেক ইন (Check In) and চেক আউট (Check Out). Right (after check-in): "চেক ইন সম্পন্ন হয়েছে" (Check-in done) and the red Check Out button, with the reminder "You have not checked out yet. Please check out before finishing today's work." What to do Open Attendance from the Dashboard. If the location is wrong or empty, tap the refresh button.
**Screenshot 15** (`docs/ui-reference/amo/15-check_in.jpg`)

15. Check in What you see Left: after tapping চেক ইন, the time is shown and a large green button "চাপ দিয়ে ধরে রাখুন" (press and hold). Right: check-in done; the Check Out button is not active yet (it becomes active at 5 PM). What to do Check the time, then press and hold the green button until check-in completes.
**Screenshot 16** (`docs/ui-reference/amo/16-check_out.jpg`)

16. Check out What you see Left: after tapping চেক আউট, the time and a large red "press and hold" button. Right: "Already Checked-In" and "Already Checked-Out" with the message that today's attendance is complete. What to do At the end of the day tap চেক আউট, check the time, and press and hold the red button. Note: Check Out is not possible before 5 PM.
# 8. Control Call and selling
A Control Call is the AMO's own call at an outlet. From it the AMO can sell (when covering a route), assess the SR's performance at that outlet, and do a survey. The AMO must be within the outlet's range; otherwise a manual override with a photo is needed.
**Screenshot 17** (`docs/ui-reference/amo/17-control_call_select_route_and_outlet.jpg`)

17. Control Call: select route and outlet What you see Route drop-down (e.g. "DariapurDaily") and retailer drop-down "Outlet name (code-phone-cluster)", with a LIVE location icon. Alphabet bar to filter outlets by first letter. Warning "AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই" (AMO and retailer are not within range), with ম্যানুয়াল ওভাররাইড (Manual Override) and রিফ্রেশ (Refresh). What to do Select the route and the outlet. If you are at the outlet but get the warning, tap Refresh. If it still shows, tap Manual Override.
**Screenshot 18** (`docs/ui-reference/amo/18-control_call_within_range.jpg`)

18. Control Call: within range What you see Route and outlet selected, with the LIVE location icon. No range warning, so the Control Call menu appears directly: বিক্রয় (Sales), এসআর পারফ. অ্যাসেসমেন্ট (SR Performance Assessment), সার্ভে (Survey). What to do Tap the activity you want to do at this outlet.
**Screenshot 19** (`docs/ui-reference/amo/19-outlet_list.jpg`)

19. Outlet list What you see The outlets of the route: name, code, phone and cluster. What to do Scroll or use the alphabet bar, then tap the outlet.
**Screenshot 20** (`docs/ui-reference/amo/20-manual_override_and_control_call_menu.jpg`)

20. Manual override and Control Call menu What you see Left: the camera opens for a photo of the outlet. The photo also updates the outlet's location information. Right: the Control Call menu: বিক্রয় (Sales), এসআর পারফ. অ্যাসেসমেন্ট (SR Performance Assessment), সার্ভে (Survey). What to do Take a clear photo of the outlet front. Choose what to do at the outlet.
**Screenshot 21** (`docs/ui-reference/amo/21-sales_select_route.jpg`)

21. Sales: select route What you see The Sales (বিক্রয়) tile also opens the route and retailer selection. Right: the route list (e.g. LaxmipurDaily, Kuptola (Sat, Mon, Wed)). What to do Tap Select a route (একটি রুট সিলেক্ট করুন) and choose the route.
**Screenshot 22** (`docs/ui-reference/amo/22-sales_select_outlet.jpg`)

22. Sales: select outlet What you see Left: the outlet list of the route. Right: route and outlet selected, alphabet bar, and the বিক্রয় (Sales) button. What to do Choose the outlet and tap বিক্রয়.
**Screenshot 23** (`docs/ui-reference/amo/23-sales_screen_and_force_sale_reason.jpg`)

23. Sales screen and force-sale reason What you see Left (বিক্রয়, Sales): the outlet (খুচরা বিক্রেতা, retailer: name, code, phone, cluster) and route at the top, the Sale History button, and each SKU with its picture, - / + quantity box, and on the right the rate per 1,000 sticks (cart icon), discount % and stock. Right: if the AMO is outside the outlet's geo-fence, the app asks "আপনার ফোর্স সেলের কারণ কী?" (What is the reason for your force sale?) with two choices: ইন্টারনেট সমস্যা (Internet problem) and লোকেশন চেঞ্জ (Location change). What to do Choose the true reason. The sale is then recorded as a Force Sale with that reason. Tap Sale History to see what the outlet bought before. Note: Choose লোকেশন চেঞ্জ only if the outlet has really moved; its base location should then be updated (section 18.7).
**Screenshot 24** (`docs/ui-reference/amo/24-enter_the_sale_and_review.jpg`)

24. Enter the sale and review What you see Left (Sales): route and outlet at the top, the button পূর্বের সেল ডাটা দেখুন (View previous sale data), and each SKU with - / + to enter quantity, its rate, discount % and line value. Total at the bottom and এগিয়ে যান (Proceed). Right (নিরীক্ষণ, Review): SKU, quantity and price, totals by category and grand total; the checkbox "এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন" (Mark this sale as due); buttons প্রোডাক্ট QC and প্রিন্ট (Print). What to do Enter the quantity the retailer wants for each SKU and tap এগিয়ে যান. Check the review. If the retailer pays later, tick the due box. Tap প্রিন্ট to save and print the memo. The memo can be printed again later from Memo.
**Screenshot 25** (`docs/ui-reference/amo/25-partial_payment_due.jpg`)

25. Partial payment (due) What you see Left: after ticking due, "পরিশোধিত টাকার পরিমাণ লিখুন" (Enter amount paid): enter the amount received now; the remaining due (বাকি) is shown. Right: the review shows "Baki 48 টাকা" (due amount). What to do Enter the amount collected now (it must be less than the total) and tap হ্যাঁ (Yes). The remaining due stays on the outlet until it is collected (section 16).
**Screenshot 26** (`docs/ui-reference/amo/26-view_previous_sale_data.jpg`)

26. View previous sale data What you see Left: the Sales screen with পূর্বের সেল ডাটা দেখুন. Right: সেল ডাটা (Sell data): the outlet's earlier sale by SKU, quantity and value, for the date chosen at the top. What to do Tap the button, then choose any date to see what the outlet bought that day.
**Screenshot 27** (`docs/ui-reference/amo/27-sr_performance_assessment.jpg`)

27. SR Performance Assessment What you see The outlet and route at the top. Brands listed: Maxim, Avon, ARIS, Marise, Black Diamond, Sunmoon, Supreme, Special Abul Bidi, Existing Abul Bidi/42 No., Ananda Bidi, Abul Bidi Gold, Abul Bidi Style, Aster, Flame Box, Salmon, ESSE, MAX, Maxim - Platinum Series. ডিস্ট্রিবিউশন পারফর্মেন্স (Distribution Performance): tick every brand present at the outlet; tick it even if it is out of stock at the moment of assessment. OOS Performance: tick the SKUs the outlet sells but has no stock of right now (out of stock). POSM: হ্যাঁ (Yes) or না (No): whether a poster or banner is at the outlet. What to do Tick the boxes, choose Yes/No for POSM and tap সংরক্ষণ (Save).
**Screenshot 28** (`docs/ui-reference/amo/28-assessment_saved.jpg`)

28. Assessment saved What you see Left: the completed assessment. Right: "ডেটা সফলভাবে সংরক্ষিত হয়েছে" (Data saved successfully). What to do Tap ওকে (OK).
# 9. Joint Call (SR call assessment)
In a Joint Call the AMO goes with the SR, watches the SR's call at an outlet and scores it.
**Screenshot 29** (`docs/ui-reference/amo/29-select_the_route.jpg`)

29. Select the route What you see Left: the route list. Right: the empty Joint Call screen: "একটি রুট সিলেক্ট করুন" (Select a route) and "রিটেইলার নির্বাচন করুন" (Select retailer). What to do Tap Joint Call on the Dashboard and choose the route.
**Screenshot 30** (`docs/ui-reference/amo/30-select_the_outlet.jpg`)

30. Select the outlet What you see Left: if you are not near the outlet, the warning "AMO and retailer are not within range" appears with Manual Override and Refresh. Right: within range, the tile এসআর কল অ্যাসেসমেন্ট (SR Call Assessment) appears. What to do Select the outlet where the SR is calling. Use Refresh or Manual Override as in Control Call.
**Screenshot 31** (`docs/ui-reference/amo/31-sr_call_assessment.jpg`)

31. SR Call Assessment What you see The outlet and route, and the instruction: rate how the SR did each step, 1 = lowest (Low), 5 = highest (উচ্চ). 5-stage sales call assessment: 1) Summarise the situation (check OHS count, OOS, product quality, opportunity and issues); 2) ধারণাটি বর্ণনা করুন, Describe the concept (explain SOQ requirement, present the offer, campaign communication); 3) Explain how it works (offer modality, highlight business benefit to the retailer); 4) সুবিধা পুনঃব্যক্ত করা, Reaffirm the benefits (answer the retailer's questions, repeat the benefits); 5) সফলভাবে সমাপ্ত করা, Close successfully (memo / execution as agreed, ensure NCR). রিলেশনশিপ অ্যাসেসমেন্ট (Relationship assessment): greeting, knowing the retailer's name, warmth with the retailer. সার্ভিস কোয়ালিটি অ্যাসেসমেন্ট (Service quality assessment): visiting on time, correct memo and its safekeeping, solving the retailer's complaints. What to do Tap the SR Call Assessment tile, watch the SR's call, and give 1 to 5 stars for each of the seven items.
**Screenshot 32** (`docs/ui-reference/amo/32-save_the_assessment.jpg`)

32. Save the assessment What you see Left: the relationship and service quality sections and the সংরক্ষণ (Save) button. Right: "ডেটা সফলভাবে সংরক্ষিত হয়েছে" (Data saved successfully). What to do Tap সংরক্ষণ, then ওকে.
# 10. Team Location
**Screenshot 33** (`docs/ui-reference/amo/33-live_location_of_srs.jpg`)

33. Live location of SRs What you see Left: the SR list. Right: the selected SR's name and phone on Google Maps at his live position. What to do Tap Team Location, choose an SR, and zoom the map as needed.
# 11. Team Performance
**Screenshot 34** (`docs/ui-reference/amo/34-monthly_and_till_date_target.jpg`)

34. Monthly and Till Date target What you see Left: মাসিক লক্ষ্য (Monthly Target). Right: Till Date Target. Cards for the Zone and for each route: Cigarette, Lighter, Match (and Bidi), each with achievement / target, % and a bar (red, amber, green). Example: Cigarette 1,698,680 / 2,290,000 = 74%. Till Date Target = the part of the monthly target due up to today. What to do Switch between the two tabs at the top. Tap বিস্তারিত (Details) on a card to see the brand detail.
**Screenshot 35** (`docs/ui-reference/amo/35-details_by_brand.jpg`)

35. Details by brand What you see The zone's bars on top and a table below: Item (brand), টার্গেট (Target), অর্জন (Achievement), বাকি (Remaining) and %. Left: Monthly; right: Till Date. What to do Scroll the table to see all brands.
**Screenshot 36** (`docs/ui-reference/amo/36-one_category_only.jpg`)

36. One category only What you see Tap a category (e.g. বিড়ি, Bidi) to see the target and achievement of that category only. What to do Tap Cigarette, Bidi, Lighter or Match on the card.
# 12. Task Delegation
**Screenshot 37** (`docs/ui-reference/amo/37-assigned_tasks.jpg`)

37. Assigned Tasks What you see The list of tasks you assigned: outlet, task description and completion date, with status (e.g. চলমান, Running). The orange + button adds a task. What to do Tap + to assign a new task.
**Screenshot 38** (`docs/ui-reference/amo/38-assign_a_task.jpg`)

38. Assign a task What you see Left: route, outlet (with outlet name, owner, SR name and SR mobile), Task type (e.g. General Task), completion date, description, and টাস্ক বরাদ্দ করুন (Assign Task). Right: the task appears in the list with "Save Successfully!". What to do Choose the route and outlet, the task type and date, write the description and tap Assign Task.
# 13. Live Dashboard
**Screenshot 39** (`docs/ui-reference/amo/39-live_data_by_route.jpg`)

39. Live data by route What you see Top: select AMO (e.g. AMO-GulshanAMO), select a route (e.g. Gulshan-1Daily), the filter button, and refresh at the top right. বিক্রয় (Sales): today's sales of Cigarette, Bidi, Lighter, Match. লাইভ স্ট্রাইক রেট (Live Strike Rate): Target Outlet, Successful Calls and % (e.g. 259 / 641 = 40.41%). জিও ফেন্সিং স্ট্যাটাস (Geo Fencing Status): Geo validation, photo validation and Total Call (e.g. 288 geo-validated = 99.31%). লগইন & বিক্রয় জমা স্ট্যাটাস: Login status and Bikroy Joma status of the selected routes (right screenshot). What to do Select the route(s) and tap the filter button. The data is live, updated till the current time.
# 14. SR Stock
**Screenshot 40** (`docs/ui-reference/amo/40-sr_list_and_lifted_stock.jpg`)

40. SR list and lifted stock What you see Left: the SRs of your zone with route, phone and total outlets. Right: লিফটেড স্টক (Lifted Stock) of the chosen SR, by SKU and issue quantity. What to do Tap SR Stock, then tap an SR to see what he lifted today.
# 15. Stock
**Screenshot 41** (`docs/ui-reference/amo/41-stock_issue.jpg`)

41. Stock issue What you see Columns: এসকেইউ (SKU), ইস্যু (Issue) with - / + and স্টক (Stock). Bottom: totals by category (Cigarette, Lighter, Match): total issue and stock; buttons সংরক্ষণ (Save) and প্রিন্ট (Print). The printer icon at the top right is red when the printer is not connected. What to do Each morning enter the quantity you take for each SKU and tap Save. Connect the printer from this screen (section 6) before selling.
# 16. Memo: reprint, edit and due collection
**Screenshot 42** (`docs/ui-reference/amo/42-find_and_reprint_a_memo.jpg`)

42. Find and reprint a memo What you see Left: route and outlet selection (an outlet with due shows the due amount and বাকি), and the মেমো (Memo) button. Right: the memo: SKU, quantity, price, totals, discount and grand total, with প্রিন্ট (Print) and এডিট (Edit). What to do Select the route and outlet and tap মেমো. Connect the printer and tap প্রিন্ট to print again. Tap এডিট to edit the sale.
**Screenshot 43** (`docs/ui-reference/amo/43-collect_a_due.jpg`)

43. Collect a due What you see A memo marked as due shows "এই বিক্রয়টি বাকিতে করা হয়েছে" (This sale was made on due) and the green button পরিশোধিত করুন (Mark as paid). What to do Select the outlet with due, tap মেমো, then পরিশোধিত করুন.
**Screenshot 44** (`docs/ui-reference/amo/44-confirm_payment.jpg`)

44. Confirm payment What you see Left: "আপনি কি নিশ্চিত?" (Are you sure?) with হ্যাঁ / না. Right: "This memo has been marked as paid". What to do Tap হ্যাঁ (Yes), then ওকে. The due mark is removed from the outlet.
# 17. Sales Summary
**Screenshot 45** (`docs/ui-reference/amo/45-today_s_sales_summary.jpg`)

45. Today's sales summary What you see "Your whole day's sales details will be here." Total sales (মোট বিক্রয়), value (মূল্য), discount (ডিসকাউন্ট) and grand total (সর্ব মোট), with প্রিন্ট (Print). "কিছু পাওয়া যায়নি" means no sales yet. What to do At the end of the day open Summary and print it if needed.
# 18. Outlet
**Screenshot 46** (`docs/ui-reference/amo/46-outlet_menu.jpg`)

46. Outlet menu What you see এস আর থেকে আসা অনুরোধ গুলো যাচাই করুন (Verify requests from SRs): নতুন আউটলেট যাচাইকরণ (New outlet verification), আউটলেট বন্ধের যাচাইকরণ (Outlet close verification), আউটলেট সংশোধনের যাচাইকরণ (Outlet correction verification). Red badges show pending requests. দোকান সংক্রান্ত অপারেশন (Shop operations): নতুন দোকান (New shop), স্থায়ী বন্ধ (Permanent close), তথ্য পরিবর্তন করুন (Change information), আপডেট বেস (Update base), ক্লাস্টার (Cluster: works the same as in the SR app). What to do Verify SR requests first, then use Shop operations for your own changes.
## 18.1 Verify a new outlet from an SR
**Screenshot 47** (`docs/ui-reference/amo/47-new_outlet_request.jpg`)

47. New outlet request What you see Left: the list of new shops sent by SRs (route, cluster, shop name, owner, phone). Right: the request opened, with Sub-Channel and Geo Classification to fill. What to do Tap a request, check the details, and choose the Sub-Channel and Geo Classification.
**Screenshot 48** (`docs/ui-reference/amo/48-photo_and_save.jpg`)

48. Photo and save What you see Left: the camera after tapping GEO এবং ছবি ধারণ করুন (Capture GEO and photo). Right: the completed form with বাতিল (Cancel) and সংরক্ষণ (Save). What to do Take the outlet photo at the outlet and tap সংরক্ষণ.
**Screenshot 49** (`docs/ui-reference/amo/49-new_outlet_saved.jpg`)

49. New outlet saved What you see "ডেটা সফলভাবে সংরক্ষিত হয়েছে" (Data saved successfully). What to do Tap ওকে.
## 18.2 Verify an outlet close request
**Screenshot 50** (`docs/ui-reference/amo/50-close_request.jpg`)

50. Close request What you see Left: outlets that SRs asked to close permanently. Right: the request opened, with বাতিল (Cancel) and সংরক্ষণ (Save). What to do Check the outlet. Tap সংরক্ষণ to approve the close, or বাতিল to reject it, and confirm with হ্যাঁ.
**Screenshot 51** (`docs/ui-reference/amo/51-close_request_updated.jpg`)

51. Close request updated What you see "Data Updated Successfully". What to do Tap ওকে.
## 18.3 Verify an outlet correction request
**Screenshot 52** (`docs/ui-reference/amo/52-correction_request.jpg`)

52. Correction request What you see Left: outlets whose information an SR changed. Right: the request with shop name, owner, mobile, Sub-Channel and Geo Classification. What to do Correct anything that is wrong, tap সংরক্ষণ and confirm with হ্যাঁ.
**Screenshot 53** (`docs/ui-reference/amo/53-correction_updated.jpg`)

53. Correction updated What you see "Data Updated Successfully". What to do Tap ওকে.
## 18.4 Add a new shop
**Screenshot 54** (`docs/ui-reference/amo/54-select_route_and_cluster.jpg`)

54. Select route and cluster What you see Left: "Select a route". Right: route selected; "ক্লাস্টার নির্বাচন করুন" (Select a cluster). What to do Choose the route, then the cluster.
**Screenshot 55** (`docs/ui-reference/amo/55-new_shop_form.jpg`)

55. New shop form What you see Shop name (দোকানের নাম), owner name (দোকানের মালিকের নাম), mobile number (মোবাইল নম্বর), Sub-Channel, Geo Classification, GEO এবং ছবি ধারণ করুন and সংরক্ষণ. What to do Fill in every field, tap GEO এবং ছবি ধারণ করুন at the shop and take the photo.
**Screenshot 56** (`docs/ui-reference/amo/56-photo_and_save.jpg`)

56. Photo and save What you see Left: the camera. Right: "ডেটা সফলভাবে সংরক্ষিত হয়েছে" (Data saved successfully). What to do Take the photo, tap সংরক্ষণ, then ওকে.
## 18.5 Close a shop permanently
**Screenshot 57** (`docs/ui-reference/amo/57-permanent_close.jpg`)

57. Permanent close What you see Left: cluster, retailer and the shop details, with সংরক্ষণ. Right: "আপনি কি নিশ্চিত? এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে" (Are you sure? This shop will be closed permanently). What to do Choose the cluster and retailer, tap সংরক্ষণ, then হ্যাঁ.
**Screenshot 58** (`docs/ui-reference/amo/58-close_saved.jpg`)

58. Close saved What you see "ডেটা সফলভাবে সংরক্ষিত হয়েছে" (Data saved successfully). What to do Tap ওকে.
## 18.6 Change shop information
**Screenshot 59** (`docs/ui-reference/amo/59-change_information_form.jpg`)

59. Change information form What you see Route, retailer, alphabet bar, cluster, shop name, owner name, mobile number, GEO এবং ছবি ধারণ করুন and সংরক্ষণ. What to do Select the route and outlet and correct the information. Tap GEO এবং ছবি ধারণ করুন at the shop.
**Screenshot 60** (`docs/ui-reference/amo/60-photo_taken.jpg`)

60. Photo taken What you see Left: the camera. Right: "ছবি ধারণ করা সম্পন্ন হয়েছে" (Photo captured). What to do Take the photo and tap ওকে.
**Screenshot 61** (`docs/ui-reference/amo/61-save_the_change.jpg`)

61. Save the change What you see Left: "আপনি কি নিশ্চিত? এই পরিবর্তন সংরক্ষণ করা হবে" (Are you sure? This change will be saved). Right: "Data saved successfully". What to do Tap সংরক্ষণ, then হ্যাঁ, then ওকে.
## 18.7 Update an outlet's base location (Base Recalibration)
**Screenshot 62** (`docs/ui-reference/amo/62-base_recalibration.jpg`)

62. Base Recalibration What you see Left: বেস রিক্যালিব্রেশন with route and retailer selection and আপডেট বেস (Update base). Right: the route list. What to do Choose the route.
**Screenshot 63** (`docs/ui-reference/amo/63-select_the_outlet.jpg`)

63. Select the outlet What you see Left: route and outlet selected, alphabet bar and the green আপডেট বেস button. Right: "Are you sure you want to update the retailer's location?" with আপডেট / বাতিল. What to do Stand at the outlet, tap আপডেট বেস, then আপডেট. Take a photo of the outlet when the camera opens.
**Screenshot 64** (`docs/ui-reference/amo/64-confirm_the_location.jpg`)

64. Confirm the location What you see অবস্থান নিশ্চিত করুন (Confirm location): Google Maps with your position and the button নিশ্চিত করুন (Confirm). What to do Check the pin is on the outlet and tap নিশ্চিত করুন. Note: The new base location becomes the outlet's geo-fence point. It should be done only at the outlet.
# 19. Sales Submit (Bikroy Joma)
**Screenshot 65** (`docs/ui-reference/amo/65-sync_data.jpg`)

65. Sync data What you see Device status (online), and a table বিষয় (Item), ডিভাইস (Device), সার্ভার (Server) for: outlet, sales, stock, QC, promotion, distribution and OOS performance, price, joint call, survey. Message: "Before finishing today's work, sync all operation data and submit sales from this section." Buttons ডাটা সিঙ্ক করুন (Sync data) and বিক্রয় জমা (Sales submit). Sales submit is enabled only after sync. What to do When all calls are finished, tap ডাটা সিঙ্ক করুন and wait. Check that Device and Server figures match, then tap বিক্রয় জমা.
**Screenshot 66** (`docs/ui-reference/amo/66-due_warning_and_success.jpg`)

66. Due warning and success What you see Left: "You still have due with 1 retailer. Do you want to submit sales?" with হ্যাঁ / না. Right: "বিক্রয় সফল ভাবে জমা হয়েছে" (Sales submitted successfully). What to do Tap না to go back and collect the due first, or হ্যাঁ to submit anyway. Tap ওকে when the success message appears. Note: After Bikroy Joma no more sales can be made for the day.
# 20. Astha
Astha shows the STD (volume) and memo targets and achievement of Astha (partner) outlets, by route or by outlet, for a quarter.
**Screenshot 67** (`docs/ui-reference/amo/67-route_info_std_target.jpg`)

67. Route info: STD target What you see Tabs রুটের তথ্য (Route info) and দোকান ভিত্তিক তথ্য (Shop-wise info). Choose the route, year (বছর) and quarter (কোয়ার্টার), e.g. 2025, Q-4 (Oct-Dec). Right: month buttons and the এসটিডি টার্গেট (STD target) table: brand, target, achievement, remaining, %. What to do Choose route, year and quarter. Select one month or all three months.
**Screenshot 68** (`docs/ui-reference/amo/68-route_info_memo_target.jpg`)

68. Route info: Memo target What you see মেমো টার্গেট (Memo target): target, achievement, remaining and % of memos (All Brand). What to do Tap মেমো টার্গেট.
**Screenshot 69** (`docs/ui-reference/amo/69-shop_wise_info.jpg`)

69. Shop-wise info What you see Left: the Astha outlets of the route with name, owner, phone, Sub-Channel (Diamond, Gold ...), Wing, Division, Territory, Zone. Right: one outlet with its STD target table. What to do Tap দোকান ভিত্তিক তথ্য, choose an outlet, then year, quarter and month(s).
**Screenshot 70** (`docs/ui-reference/amo/70-shop_wise_memo_target.jpg`)

70. Shop-wise memo target What you see The outlet's memo target and achievement for the selected months. What to do Tap মেমো টার্গেট.
# 21. Report
**Screenshot 71** (`docs/ui-reference/amo/71-std_memo_report.jpg`)

71. STD Memo Report What you see Left: রিপোর্টসমূহ (Reports): এসটিডি মেমো রিপোর্ট (STD Memo Report) and এখন পর্যন্ত বিক্রয় সারসংক্ষেপ (Sales till now summary). Right: STD Memo Report for a date range (default: this month to today): each route with Cigarette, Lighter ... and the total. What to do Tap Report, then the report you need. Change the dates at the top; swipe left to see all categories.
**Screenshot 72** (`docs/ui-reference/amo/72-sales_till_now_summary.jpg`)

72. Sales till now summary What you see Left: the Zone and each route: category, target, sales, %, memo, and বিস্তারিত (Details). Right: a route's detail: CPR, total memo and each brand's target, sales, % and memo. What to do Tap বিস্তারিত on a route to see brand detail.
# 22. Sales Journey
Sales Journey (বিক্রয় যাত্রা) shows, for each SR of your area, today's calls in the order they were made: when each call started and ended, how long it took, how long the SR took to walk to the next outlet and how far he moved. Use it to check whether the SR follows the route properly and spends enough time at each outlet.
**Screenshot 73** (`docs/ui-reference/amo/73-sr_list.jpg`)

73. SR list What you see One card per SR route: SR name, route (e.g. Gulshan-1Daily, Tal Tola (Sun, Tue, Thu)), phone and মোট আউটলেট (total outlets on the route). An SR with more than one route has one card per route. A vacant route shows "Vacant" and no phone number. What to do Tap a card (›) to open that SR's journey for today.
**Screenshot 74** (`docs/ui-reference/amo/74-journey_timeline.jpg`)

74. Journey timeline What you see Each card is one call, in time order: the sale status (New Sale; Edited Sale cards appear in pink later in the list), the outlet name (code-phone-cluster), start and end time (e.g. 9:57:43 AM - 10:05:30 AM) and duration (e.g. ৭ মিনিট ৪৬ সেকেন্ড = 7 min 46 sec). দূরত্ব (Distance): distance from the previous call's outlet (e.g. 277.32 m). মোট (Total): total distance covered so far today (e.g. 391.54 m). Between cards: the walking icon with "... পরে" = time taken to reach the next outlet (e.g. ৮ মিনিট ৫৬ সেকেন্ড পরে = after 8 min 56 sec). What to do Scroll down to follow the whole day. Look for very short calls, long gaps between calls, or long distances, and discuss them with the SR.
# 23. Settings and Log Out
**Screenshot 75** (`docs/ui-reference/amo/75-settings.jpg`)

75. Settings What you see Three options: language (বাংলা / en), PDA টু সাপোর্ট (send the data file to support) and লগ আউট (Log Out). Right: the language list. What to do Choose en for English or বাংলা for Bangla.
**Screenshot 76** (`docs/ui-reference/amo/76-send_data_file_to_support.jpg`)

76. Send data file to support What you see "সফল - সিঙ্ক ফাইল পাঠানো হয়ে গেছে" (Success - sync file sent). What to do Tap PDA টু সাপোর্ট when support asks for your data file, then ওকে.
**Screenshot 77** (`docs/ui-reference/amo/77-log_out.jpg`)

77. Log Out What you see "আপনি কি নিশ্চিত যে লগআউট করতে চান?" (Are you sure you want to log out?) with হ্যাঁ / না. What to do Tap লগ আউট, then হ্যাঁ. Note: Do Bikroy Joma before logging out, so no data is left on the phone.
# 24. Quick answers (FAQ)
| Question | Answer |
|---|---|
| How do I install or update the app? | Install from the APK (Screenshots 1-2). When "NEW UPDATE" appears, tap Download App & Install, allow unknown apps, then Update (Screenshots 5-7). |
| The app asks for location, camera, audio or Bluetooth. | Always allow ("While using the app" / Allow). The app needs them for attendance, geo-fencing, photos and printing. |
| How do I connect the printer? | Pair it in phone Bluetooth with PIN 0000, then open Stock and tap the printer icon until it turns green (section 6). |
| How do I check in and out? | Attendance > চেক ইন > press and hold the green button. After 5 PM চেক আউট > press and hold the red button (section 7). Check out is not possible before 5 PM. |
| It says AMO and retailer are not within range. | Tap Refresh. If you are at the outlet and it still shows, tap Manual Override and take the outlet photo. |
| How do I sell to an outlet? | Sales (or Control Call > Sales) > route > outlet > enter quantities > এগিয়ে যান > check > প্রিন্ট (section 8). |
| The retailer will pay later. | On the review screen tick "Mark this sale as due" and enter the amount received now. Collect the rest later from Memo > পরিশোধিত করুন (section 16). |
| How do I reprint or edit a memo? | Memo > route > outlet > মেমো > প্রিন্ট or এডিট. |
| How do I assess an SR? | Joint Call > route > outlet > SR Call Assessment, give 1-5 stars and save (section 9). Use Control Call > SR Performance Assessment for distribution, OOS and POSM. |
| Where do I see my SRs? | Team Location for live position; SR Stock for lifted stock; Live Dashboard for live sales, strike rate, geo-fencing and Bikroy Joma. |
| The app asks the reason for force sale. | You are outside the outlet's geo-fence. Choose ইন্টারনেট সমস্যা (Internet problem) or লোকেশন চেঞ্জ (Location change). If the outlet moved, update its base location. |
| How do I check an SR's route-following? | Sales Journey > tap the SR: each call with time, duration, walking time and distance (section 22). |
| How do I see targets? | Team Performance (Monthly or Till Date; Details for brands). Dashboard target cards show today's and this month's call targets. |
| How do I approve an SR's new outlet / close / correction request? | Outlet > the matching verification button > open the request > check > Save (section 18). |
| How do I fix an outlet's location? | Outlet > আপডেট বেস: at the outlet choose the route and outlet, Update base, take the photo and confirm on the map. |
| How do I finish the day? | Bikroy Joma: ডাটা সিঙ্ক করুন, check Device = Server, then বিক্রয় জমা. Then Attendance > চেক আউট. |
| Can I sell after Bikroy Joma? | No. No more sales can be made for that day. |
| How do I change the language? | Settings > language > en or বাংলা. |
| Support asked for my data file. | Settings > PDA টু সাপোর্ট. |
# Annex A. Bangla screen labels in English
| Bangla label | English meaning |
|---|---|
| একটি রুট সিলেক্ট করুন | Select a route |
| রিটেইলার নির্বাচন করুন | Select retailer (outlet) |
| ক্লাস্টার নির্বাচন করুন | Select a cluster |
| ম্যানুয়াল ওভাররাইড | Manual override |
| রিফ্রেশ | Refresh |
| AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই | AMO and retailer are not within range |
| চেক ইন / চেক আউট | Check in / Check out |
| চাপ দিয়ে ধরে রাখুন | Press and hold |
| বিক্রয় | Sales |
| এগিয়ে যান | Proceed |
| নিরীক্ষণ | Review |
| পূর্বের সেল ডাটা দেখুন | View previous sale data |
| এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন | Mark this sale as due |
| বাকি | Due / remaining |
| পরিশোধিত করুন | Mark as paid |
| প্রিন্ট / এডিট | Print / Edit |
| সংরক্ষণ | Save |
| বাতিল | Cancel |
| হ্যাঁ / না | Yes / No |
| ওকে | OK |
| মোট / সর্বমোট | Total / Grand total |
| ডিসকাউন্ট | Discount |
| মূল্য | Price / value |
| টার্গেট / অর্জন / বাকি | Target / Achievement / Remaining |
| মাসিক লক্ষ্য | Monthly target |
| বিস্তারিত | Details |
| সিগারেট / বিড়ি / লাইটার / ম্যাচ | Cigarette / Bidi / Lighter / Match |
| এসকেইউ / ইস্যু / স্টক | SKU / Issue / Stock |
| লিফটেড স্টক | Lifted stock |
| এসআর লিস্ট | SR list |
| মোট আউটলেট | Total outlets |
| লাইভ স্ট্রাইক রেট | Live strike rate |
| টার্গেট আউটলেট | Target outlet |
| জিও ফেন্সিং স্ট্যাটাস | Geo-fencing status |
| জিও ভ্যালিডেশন / ছবি ভ্যালিডেশন | Geo validation / photo validation |
| নতুন দোকান | New shop |
| স্থায়ী বন্ধ | Permanent close |
| তথ্য পরিবর্তন করুন | Change information |
| আপডেট বেস / বেস রিক্যালিব্রেশন | Update base / base recalibration |
| দোকানের নাম / দোকানের মালিকের নাম / মোবাইল নম্বর | Shop name / owner name / mobile number |
| GEO এবং ছবি ধারণ করুন | Capture GEO location and photo |
| অবস্থান নিশ্চিত করুন | Confirm location |
| ডাটা সিঙ্ক করুন | Sync data |
| বিক্রয় জমা | Sales submit (Bikroy Joma) |
| ডিভাইস / সার্ভার | Device / Server |
| রুটের তথ্য / দোকান ভিত্তিক তথ্য | Route info / Shop-wise info |
| বছর / কোয়ার্টার | Year / Quarter |
| এসটিডি টার্গেট / মেমো টার্গেট | STD target / Memo target |
| এসটিডি মেমো রিপোর্ট | STD memo report |
| এখন পর্যন্ত বিক্রয় সারসংক্ষেপ | Sales till now summary |
| সারসংক্ষেপ | Summary |
| আপনার ফোর্স সেলের কারণ কী? | What is the reason for your force sale? |
| ইন্টারনেট সমস্যা / লোকেশন চেঞ্জ | Internet problem / Location change |
| খুচরা বিক্রেতা | Retailer |
| বিক্রয় যাত্রা | Sales Journey |
| দূরত্ব / মোট | Distance / Total |
| মিনিট / সেকেন্ড / পরে | Minutes / Seconds / after |
| ডিস্ট্রিবিউশন পারফর্মেন্স | Distribution performance |
| রিলেশনশিপ / সার্ভিস কোয়ালিটি অ্যাসেসমেন্ট | Relationship / Service quality assessment |
| উচ্চ | High |
| ডেটা সফলভাবে সংরক্ষিত হয়েছে | Data saved successfully |
| আপনি কি নিশ্চিত? | Are you sure? |
| কিছু পাওয়া যায়নি / তথ্য পাওয়া যায়নি | Nothing found / No data found |
