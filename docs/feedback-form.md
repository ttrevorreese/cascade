# Feedback form setup

Settings > Feedback opens a Google Form in the browser once the form exists. The developer's
address never shows, and a small script on the form emails every submission with an emoji in
the subject:

| Kind | Subject |
|---|---|
| Feature request | 💡 Cascade feature request: *first words of the message* |
| Bug report | 🐞 Cascade bug report: *first words* |
| Something else | 💬 Cascade feedback: *first words* |

Until `Feedback.FORM_URL` is set, the app keeps opening the email app instead.

It takes about ten minutes, and costs nothing. Use the cascadealarmgroup.app@gmail.com account.

## 1. Make the form

1. Go to <https://forms.google.com> signed in as cascadealarmgroup.app@gmail.com, and start a
   blank form. Title: **Cascade feedback**.
2. Add these questions, in this order, with exactly these words (the app fills in the first and
   the last, and the script matches the options word for word):

   | Question | Type | Required | Options |
   |---|---|---|---|
   | What is it about? | Multiple choice | Yes | `Feature request`, `Bug report`, `Something else` |
   | Tell us more | Paragraph | Yes | |
   | Your email, if you would like a reply | Short answer | No | |
   | Phone | Short answer | No | |

3. Settings (the cog in the form editor) > Responses: leave **Collect email addresses** off, and
   **Limit to 1 response** off (that one needs a Google sign-in).

## 2. Add the script that emails you

1. In the form editor: the three-dot menu > **Script editor** (Apps Script opens).
2. Replace everything in `Code.gs` with the script below. Put your own addresses in `RECIPIENTS`
   here, in the Apps Script editor only, not in the repository: it is public.
3. Save, then run `install` once (pick it in the function menu, press Run). Google asks for
   permission to read the form and send email as you: allow it. ("Google hasn't verified this
   app" is expected for your own script: Advanced > Go to project.)
4. Submit a test response from the form's preview (the eye icon). Both inboxes should get it
   within a minute.

```javascript
// Cascade feedback: emails each response, with an emoji per kind in the subject.
// Add or remove addresses here; separate them with commas.
const RECIPIENTS = ['cascadealarmgroup.app@gmail.com', 'YOUR_PERSONAL_ADDRESS@gmail.com'];

const KINDS = {
  'Feature request': '💡 Cascade feature request',
  'Bug report': '🐞 Cascade bug report',
  'Something else': '💬 Cascade feedback',
};

function install() {
  const form = FormApp.getActiveForm();
  ScriptApp.getProjectTriggers().forEach((t) => ScriptApp.deleteTrigger(t));
  ScriptApp.newTrigger('onSubmit').forForm(form).onFormSubmit().create();
}

function onSubmit(e) {
  const answers = {};
  e.response.getItemResponses().forEach((r) => {
    answers[r.getItem().getTitle()] = String(r.getResponse());
  });
  const kind = answers['What is it about?'] || 'Something else';
  const message = answers['Tell us more'] || '';
  const replyTo = (answers['Your email, if you would like a reply'] || '').trim();
  const phone = answers['Phone'] || 'not given';
  const preview = message.replace(/\s+/g, ' ').trim().slice(0, 60);
  const subject = (KINDS[kind] || KINDS['Something else']) + (preview ? ': ' + preview : '');
  const body = [
    message,
    '',
    '—',
    'Kind: ' + kind,
    'Phone: ' + phone,
    'Reply to: ' + (replyTo || 'no address given'),
    'Sent: ' + e.response.getTimestamp(),
  ].join('\n');
  const options = { name: 'Cascade feedback' };
  if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(replyTo)) options.replyTo = replyTo;
  MailApp.sendEmail(RECIPIENTS.join(','), subject, body, options);
}
```

To stop the copies to your personal inbox later, delete it from `RECIPIENTS` and save. Nothing
in the app changes.

## 3. Connect the app

1. In the form editor: three-dot menu > **Get pre-filled link**. Pick `Bug report` for the first
   question and type `PHONE` in the Phone box, then **Get link** and copy it.
2. Send the link to Claude (or edit `ui/settings/Feedback.kt` yourself). It looks like
   `https://docs.google.com/forms/d/e/1FAIpQ.../viewform?usp=pp_url&entry.123=Bug+report&entry.456=PHONE`:
   - `FORM_URL` = everything before `?`
   - `FORM_KIND_ENTRY` = the number after the `entry.` that says `Bug+report` (`123`)
   - `FORM_PHONE_ENTRY` = the number after the `entry.` that says `PHONE` (`456`)
3. Rebuild. Settings > Feedback then says "Opens a short form in your browser".

Google limits a free account to about 100 emails a day from scripts, far more than feedback needs.
The privacy policy already covers the form ("Sending feedback").
