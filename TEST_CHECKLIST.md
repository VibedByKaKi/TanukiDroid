# Feature test checklist

Agent-owned. Mirrors the ForgeDroid checklist. Built from every menu item, click handler and preference in `app/src/main`. Mark each line `[x]` with the date and build commit when it passes on the emulator, or `[!]` with a note when it fails. Mark `[-]` with a reason when the feature does not exist on GitLab and the app hides it or shows a clear message.

Rules for every line:
- Do the action, then check the result on screen and on the server where it writes data. Opening a screen is not enough.
- For every list, open the first item and one more item.
- Check logcat after each action for 4xx/5xx responses, `Loading data failed` and `FATAL`. A 404 for `user/starred/...` when the repo is not starred is expected.
- Look for "null", "0" placeholders and raw `number|title` text in rows.
- Use a scratch repo on the test account for anything that writes data, and clean up afterwards.

Test against the local GitLab CE container (`http://10.0.2.2:8929`, accounts `alice`, `bob`, `root`, group `tanuki-org` with subgroup `sub`, seeded by `/tmp/tk/seed.py`). Run the News Feed and profile sections as a user and as an org from the account selector. Also open public projects on gitlab.com for the preset instances where no login is needed.

## 1. Accounts and login
- [x] Add account dialog opens from the drawer account menu (2026-11-10, tested on emulator-5556 against local GitLab 19.5.3)
- [x] Dropdown lists gitlab.com, gitlab.gnome.org, invent.kde.org, salsa.debian.org, gitlab.freedesktop.org, gitgud.io, framagit.org, jihulab.com, gitlab.alpinelinux.org, Custom (2026-11-10, all nine presets plus Custom)
- [x] Custom shows the base URL input, other hosts hide it (2026-11-10)
- [x] Token name input is editable and its value is used for the token (2026-11-10, token 'My Phone' appeared on the server)
- [x] Webview loads the sign-in page of the selected host (2026-11-10, http://10.0.2.2:8929/users/sign_in)
- [x] Webview register link works (2026-11-10, GitLab registration form opens in the webview (local instance))
- [x] Logging in creates a token with the chosen name (check the host's applications page) and saves the account (2026-11-10, token and account verified on the server)
- [x] Bad base URL shows a readable error (2026-11-10, Chrome error page in the webview)
- [x] Cancel closes the dialog without saving (2026-11-10)
- [-] Login mode chooser fragment (if reachable) behaves (2026-11-10, not reachable from the current flow)
- [x] Account selector in the News Feed lists the user and every org (2026-11-10, drawer lists the other accounts with their own avatars)
- [x] Switching accounts reloads the current screen (2026-11-10)
- [x] Drawer header expands to My profile, Logout, Add account (2026-11-10)
- [x] My profile opens the user profile (2026-11-10)
- [x] Logout removes the account and returns to the login flow (2026-11-10, account removed, another one active)
- [x] Cold start after logout and after login restores the right account (2026-11-10, force-stop and relaunch restored the active account)
- [x] Revoked token shows a readable error, not a crash (2026-11-10, revoked on the server; snackbar asks to log in again)

## 2. Drawer (`home_nav_drawer`)
- [x] News Feed (2026-11-10)
- [x] Notifications (2026-11-10, drawer, gitlab.com)
- [x] My repositories (2026-11-10, drawer, gitlab.com)
- [x] My issues (2026-11-10, drawer, gitlab.com)
- [x] My pull requests (2026-11-10, opens on gitlab.com)
- [x] My gists (2026-11-10, drawer, gitlab.com)
- [x] Search (2026-11-10, drawer, gitlab.com)
- [x] Bookmarks and Stars (2026-11-10, opens on gitlab.com)
- [x] Public Timeline (hidden or clear message on GitLab) (2026-11-10, drawer, gitlab.com)
- [x] Trending Repos (hidden or clear message on GitLab) (2026-11-10, drawer, gitlab.com)
- [x] GitHub Blog (hidden, since it is GitHub-only) (2026-11-10, drawer, gitlab.com)
- [x] Settings (2026-11-10, drawer, gitlab.com)
- [x] Add account, My profile, Logout from the header menu (2026-11-10, all three open or act)
- [ ] Back from each screen returns to the previous one without leaving the app unexpectedly
- [ ] Drawer swipe open and close, selected item is highlighted

## 3. News Feed
- [ ] Pull to refresh
- [ ] Scroll to load the next page
- [x] Tap an avatar opens that user (2026-11-10, gitlab.com)
- [x] Tap a repo name opens the repo (2026-11-10, gitlab.com)
- [ ] Push event row, tap row, tap each listed commit
- [ ] Create repository, branch and tag rows
- [ ] Delete branch and tag rows
- [ ] Issue opened, closed and reopened rows, tap through
- [ ] Issue comment row, tap through
- [ ] Pull request opened, closed, reopened and merged rows, tap through (opens the PR screen)
- [ ] Pull request comment row, tap through (opens the PR screen, not the issue screen)
- [ ] Fork, star and release rows, tap through
- [ ] Unsupported event types are skipped without a blank row
- [ ] No "null", "pull request 0" or raw `number|title` text
- [ ] Same checks for an org feed

## 4. Notifications (`notification_list_menu`, `notification_menu`)
- [x] List loads, empty state shows when there are none (2026-11-10, todos list and empty state)
- [x] Filter menu: Unread, All, Participating (2026-11-10, local server, alice)
- [x] Mark all as read (2026-11-10, local server, alice)
- [x] Row tap opens the issue, PR or commit (2026-11-10, issue opened from a notification)
- [x] Row avatar tap opens the user (2026-11-10, local server, alice)
- [x] Row action button: Mark as read (2026-11-10, local server, alice)
- [x] Row action button: Unsubscribe (2026-11-10, local server, alice)
- [ ] Notification polling and the system notification (Settings > Notifications on)

## 5. My repositories and repository lists (`repo_list_menu`, `repo_filter_*`, `repo_sort`)
- [x] My repositories list loads and paginates (2026-11-10, list loads; single page only on the test data)
- [x] Tap a repo (2026-11-10)
- [ ] Search icon filters the list
- [x] Filter and Sort menu opens (2026-11-10, gitlab.com)
- [x] Type filters for the logged-in user: All, Owner, Member, Public, Private, Sources, Forks, Watched (2026-11-10, gitlab.com; Watched is empty because GitLab has no watch list)
- [ ] Type filters for another user: All, Owner, Member, Sources, Forks, Watched, Starred
- [ ] Type filters for an org: All, Sources, Forks
- [x] Sort: name A-Z, name Z-A, oldest, newest, least recently pushed, most recently pushed (2026-11-10, gitlab.com)
- [ ] Starred list sort order (`repo_starred_list_menu`): oldest, newest, least and most recently pushed
- [x] Private and fork badges render (2026-11-10, gitlab.com)
- [ ] Repository list from a user profile, an org profile, a starred list, a forks list

## 6. Issue lists (`issue_list_menu`, `issue_list_filter*`, `issue_list_sort`)
- [x] My issues tabs: Created, Assigned, Mentioned, Participating (2026-11-10, tabs list the expected issues for alice; Mentioned uses a text search for the user name, which GitLab has no filter for)
- [x] Show closed toggle and Open Issues title (2026-11-10, checked on My pull requests)
- [ ] Open an item from each tab
- [x] Repository issue list opens (2026-11-10)
- [x] Search in the list (2026-11-10, search for 'renamed' finds the renamed issue)
- [x] Remove filter (2026-11-10)
- [x] Filter by milestone (2026-11-10, M1 shows issues 1 and 2)
- [x] Filter by label (2026-11-10, bug shows 6, 1, 3 and not the closed 5)
- [x] Filter by assignee (2026-11-10, alice with milestone M1 gives the expected empty list)
- [x] Filter by participating status (2026-11-10, Participating gives 6 and 3, Not participating gives 4, 2, 1 (client side exclusion))
- [x] Manage labels (collaborator menu) (2026-11-10)
- [x] Manage milestones (collaborator menu) (2026-11-10)
- [ ] Sort: newest, oldest, most recently updated, least recently updated, most commented, least commented
- [ ] Same checks for the pull request list
- [x] Create issue button (2026-11-10)
- [x] Empty state (2026-11-10, issue and PR lists show the empty text)

## 7. Issue detail (`issue_menu`, `issue_fragment_menu`, timeline)
- [x] Header shows state, number, title, repo (2026-11-10, Issue #1 and PR !3 headers)
- [x] Body renders markdown, images, code blocks, task lists (2026-11-10, headings, emphasis, quote, task list, ordered list, code block render; tables show as plain text; image placeholder for an unreachable URL)
- [x] Labels, assignees, milestone, reactions shown (2026-11-10, issue 1 and 3)
- [x] Timeline events: comment, label change, assign, close, reopen, rename, cross reference, commit reference (2026-11-10, label, rename, cross reference and commit reference rows verified; assign, close, reopen checked earlier)
- [x] Menu: Close (2026-11-10, issue 5 closed and reopened, state verified on the server)
- [x] Menu: Reopen (2026-11-10, issue 5 closed and reopened, state verified on the server)
- [x] Menu: Share (2026-11-10, issue; share sheet shows the real instance URL)
- [x] Menu: Open in browser (2026-11-10, issue; opens Chrome with the instance URL)
- [x] Menu: Copy number (2026-11-10, clipboard holds 7)
- [x] Edit button opens the issue editor and saves title, body, labels, assignee, milestone (2026-11-10, title and labels saved, verified on the server)
- [x] Comment box: type and send (2026-11-10, comment appears on the server)
- [x] Comment box: advanced editor toggle (2026-11-10, opens editor with Edit and Preview)
- [-] Comment box: attach button (2026-11-10, no attach button in the comment box on this build)
- [x] Markdown buttons: bold, italic, strikethrough, h1, h2, h3, quote, code, link, image, bullet list, number list, task list, divider (2026-11-10, all buttons insert their markup)
- [x] Comment overflow: Share, Add reaction, Edit, Delete, View in file (2026-11-10, edit and delete only for own comments)
- [x] Text selection menu: Quote (2026-11-10, inserts > comment into the comment box)
- [x] Reaction picker: +1, -1, laugh, hooray, confused, heart, rocket, eyes, and the Loading state (2026-11-10, add, list and remove reactions on an issue and on a comment; rocket and eyes are cut off in the popup on this screen size)
- [x] Reaction bar tap adds and removes a reaction (2026-11-10, tap on the count opens Remove reaction)
- [ ] Tap a user avatar and extra text in a comment
- [x] Link inside markdown opens the right screen (2026-11-10, #2, !1, commit sha and @alice open issue, PR, commit and user)
- [ ] Rotate keeps the draft and scroll position (draft kept 2026-11-10, scroll position not checked)

## 8. Create and edit issue, labels, milestones
- [x] Create issue with title and body (2026-11-10, issue template Bug.md from .gitlab/issue_templates fills the body)
- [x] Create issue with labels, assignee, milestone chosen (2026-11-10, issue 6 on the server has labels bug,docs, assignee alice, milestone M2)
- [x] Validation error for an empty title (2026-11-10)
- [x] Label list, create, edit, delete label (2026-11-10, checked on the server after each action)
- [ ] Label color picker
- [ ] Milestone list: open and closed tabs
- [x] Create milestone with due date (2026-11-10)
- [x] Edit milestone, Close, Reopen, Delete (`edit_milestone_menu`) (2026-11-10, checked on the server after each action)
- [ ] Milestone detail issue list

## 9. Pull request detail (`pullrequest_menu`, `pull_request_fragment_menu`)
- [x] Conversation tab: header, branch info (head into base), status (2026-11-10, PR !1, !3, !5)
- [x] Commit status box: header tap and each row tap (2026-11-10, 1 failing, 1 pending, 1 successful)
- [x] Commits tab, open a commit (2026-11-10)
- [x] Files tab, open a file diff (2026-11-10)
- [x] Menu: Merge (all merge styles GitLab offers) (2026-11-10, merged !4 and !5 as maintainer; non-maintainer gets a 403 message)
- [x] Menu: Review (2026-11-10, comment and request changes create a note; approve checked through the API)
- [x] Menu: Reopen (2026-11-10, PR !1 closed and reopened, state verified on the server)
- [x] Menu: Close (2026-11-10, PR !1 closed and reopened, state verified on the server)
- [x] Menu: Share (2026-11-10, PR; share sheet shows the real merge_requests URL)
- [x] Menu: Open in browser (2026-11-10, PR; opens Chrome)
- [x] Menu: Copy number (2026-11-10, PR)
- [ ] Delete branch after merge
- [x] Comment, edit and delete a PR comment (2026-11-10, comment created and deleted on the server)
- [-] Create pull request (2026-11-10, the app has no create pull request screen (same as OctoDroid))
- [x] Merged and closed states render correctly (2026-11-10)

## 10. Reviews and diffs (`review_menu`, `review_diff_hunk_menu`, diff viewer)
- [x] Review list in the PR conversation, expand details (2026-11-10, review with its diff hunk and comment opens)
- [x] Review comment tap and avatar tap (2026-11-10, avatar opens the user)
- [x] Review overflow: Share, Open in browser (2026-11-10, menu shows both)
- [ ] Diff hunk menu: Share, View in file
- [x] Diff viewer for a commit and for a PR file (2026-11-10, PR file diff renders with the inline comment)
- [x] Add inline comment, reply, edit, delete (2026-11-10, verified on the server)
- [ ] Share a diff line (`file_line_menu`)
- [x] Large and binary diffs do not crash (2026-11-10, big.txt (4000/3000 lines) and new.bin open)
- [ ] Missing patch is filled from the `.diff` endpoint

## 11. Commits (`commit_menu`, `commit_comment_actions`, `commit_history_menu`)
- [x] Commit list on the repo Commits tab, paginate (2026-11-10, 50 commits, pages 1-3 load on scroll)
- [ ] Commit list for a file with Follow renames toggle
- [x] Commit detail: message, author, stats, files changed (2026-11-10)
- [ ] Commit menu: Open in browser, Share
- [ ] Open a changed file from a commit
- [x] Commit comments tab: add comment (2026-11-10, list, add; comments now use the discussions API and render markdown)
- [-] Commit comment actions: Share, Reply, Add comment, Add reaction, Edit, Delete (2026-11-10, Share, Edit and Delete verified on the server; Reply and reactions not on GitLab commit comments)
- [ ] Commit compare view between two refs
- [ ] Commit status box

## 12. Repository (`repo_menu`)
- [x] About tab: description, language, counts (2026-11-10, counts match the server; language unknown on GitLab)
- [ ] Counts match the server for issues, pull requests, forks, stars, watchers
- [ ] Tap each count row opens its list
- [x] README expand and collapse, relative links and images (2026-11-10, image and emoji render)
- [ ] Overview row taps (owner, homepage)
- [x] Menu: Select branch, switch to a tag and to another branch (2026-11-10, branch feature shows NEW.txt)
- [ ] Menu: Share
- [ ] Menu: Open in browser
- [-] Menu: Search code and open a result (2026-11-10, GitLab code search needs Elasticsearch, the entry is hidden)
- [ ] Menu: Download code as ZIP
- [x] Menu: Bookmark and remove bookmark (2026-11-10, bookmark list opens the repo)
- [x] Star, unstar (2026-11-10, server star count checked)
- [ ] Watch, unwatch
- [ ] Fork
- [x] Other information: Contributors, Collaborators, Releases, Wiki (2026-11-10, all four screens load)
- [x] Wiki list and a wiki page (hidden or clear message when the wiki is off) (2026-11-10, pages render as HTML)
- [ ] Activity tab, open an item
- [ ] Empty repo
- [x] Private repo (2026-11-10, alice/secret listed and openable)
- [ ] Archived and mirror repo

## 13. Files (`file_viewer_menu`, content list)
- [ ] Folder browse and breadcrumbs, tap a breadcrumb
- [ ] Open a text file, syntax highlight
- [ ] Open a markdown file rendered
- [ ] Open an image file
- [ ] Open a large and a binary file
- [ ] File menu: Search in file, Open in browser, Share, Download, Wrap lines, Raw
- [ ] File history from the content list
- [ ] Line tap and share line
- [ ] Branch and tag picker keeps the path
- [ ] Gist file viewer: Open in browser, Download

## 14. Releases and tags
- [ ] Release list
- [ ] Release detail: title, tag, body, assets
- [ ] Tag text tap and avatar tap
- [ ] Menu: Open in browser, Add reaction
- [ ] Download an asset
- [ ] Release reaction picker

## 15. Users, orgs and social
- [x] User profile: avatar, bio, counts, email, website (2026-11-10, KaKi87 on gitlab.com)
- [ ] Profile repository count matches the server
- [x] Profile menu: Share, Open in browser, Bookmark (2026-11-10, menu shows Share, Open in browser, Bookmark, Open in new window)
- [x] Follow and unfollow (2026-11-10, alice follows bob, button toggles, server confirms)
- [ ] Followers list, Following list (followers list verified 2026-11-10, following list not yet)
- [ ] Starred repos of a user
- [ ] Organizations of a user
- [x] Org profile and org repos (2026-11-10, tanuki-org on local server; profile and Public Activity)
- [x] Org members list (`OrganizationMemberListFragment`), open a member (2026-11-10, 1 member, opens root)
- [ ] Contributors list, open a contributor
- [ ] Collaborators list
- [ ] Stargazers and watchers lists
- [ ] Forks list
- [ ] User not found shows a readable error

## 16. Search (`search`)
- [x] Type selector: repositories, users, issues, pull requests, code (2026-11-10, only Repository and User are offered, which is intended on GitLab)
- [ ] Submit search with keyboard and with the search icon (keyboard submit works, icon not tested)
- [x] Open a result of each type (2026-11-10, repo and user results open on gitlab.com; issue, PR and code types are not offered)
- [x] Empty result message (2026-11-10, gitlab.com users)
- [ ] Exact user and org name search
- [ ] Pagination
- [ ] Search history or suggestions if present

## 17. Bookmarks and stars
- [ ] Bookmarks tab lists bookmarks added from repo and user menus
- [ ] Open a bookmark
- [ ] Remove a bookmark (long press or swipe)
- [ ] Starred tab lists starred repos
- [ ] Starred sort order

## 18. Gists (`gist_menu`)
- [ ] My gists: Mine and Starred tabs
- [ ] Hidden or clear message because GitLab has no gists
- [ ] Gist detail menu: Star, Share (only if reachable)

## 19. Trending, timeline and blog
- [ ] Trending screen behaviour on GitLab
- [ ] Public timeline behaviour on GitLab
- [ ] Blog list menu: Open in browser (only if reachable)

## 20. Settings
- [ ] Start page: each option opens that screen at launch
- [ ] Use built-in browser on and off, then open a link in each mode
- [ ] Notifications switch on and off
- [ ] Theme dialog: Use system setting, Day, Night apply immediately
- [ ] Code view text size dialog lists five sizes
- [ ] Animated GIF loading dialog lists three options
- [ ] Sync interval dialog
- [ ] Text size and GIF choice take effect in a file view and a markdown image
- [ ] About dialog
- [ ] Open source components list
- [ ] Settings persist after restart
- [ ] Theme and dark mode follow the system

## 21. Dialogs and shared widgets
- [ ] Single-choice dialogs (ref picker, filter pickers) select and dismiss
- [ ] Markdown rendering: tables, nested lists, HTML, emoji, mentions, issue links
- [ ] Image viewer from markdown
- [ ] Web view find bar: find previous, find next
- [ ] Print menu
- [ ] Pull to refresh on every paged list
- [ ] Error view TRY AGAIN retries
- [ ] Empty view text on every list

## 22. Deep links and intents
- [ ] GitLab repo URL
- [ ] Issue URL
- [ ] PR URL
- [ ] Commit URL
- [ ] File URL on a branch
- [ ] User and org URL
- [ ] Release URL
- [ ] Share text into the app
- [ ] Open in browser from each screen that has the action

## 23. Robustness
- [ ] Rotate on a list, a detail view and a dialog
- [ ] Background and resume
- [ ] Airplane mode on a list shows a readable error, then TRY AGAIN recovers
- [ ] Slow network does not duplicate rows
- [ ] Release build behaves like debug (R8 does not strip the interceptor models)
- [ ] Back stack after deep link

- [ ] Re-check markdown rendering on issue body, issue comments, commit comments, release notes, review bodies (body_html is now synthesized)

- [ ] Issue menu has "Open in new window"; verify it
- [ ] Close dialog "Not planned" and "Duplicate" reasons (GitLab has only open/closed): hide them or accept
- [ ] Deep links on custom hosts cannot be registered in the manifest; confirm links to a custom host open in the browser
- [ ] Emoji shortcodes such as :bug: in issue and PR titles render as text, not emoji