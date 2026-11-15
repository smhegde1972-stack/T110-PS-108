const $ = (selector) => document.querySelector(selector);
const form = $('#booking-form');
const venueSelect = $('#venueId');
const packageFields = $('#package-fields');
const quoteBox = $('#quote-box');
const notice = $('#notice');
let venues = [];
let vendors = [];
let enquiryPoll = null;

const money = (amount) => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(Number(amount || 0));
const localISODate = (date) => { const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000); return local.toISOString().slice(0, 10); };
const escapeHtml = (value) => String(value).replace(/[&<>"']/g, (ch) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));

function showNotice(message, isError = false) {
  notice.hidden = false;
  notice.className = `notice${isError ? ' error' : ''}`;
  notice.textContent = message;
}
function clearNotice() { notice.hidden = true; notice.textContent = ''; notice.className = 'notice'; }
function formData() { return new URLSearchParams(new FormData(form)); }

async function request(url, options = {}) {
  const response = await fetch(url, options);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || 'Something went wrong. Please try again.');
  return data;
}

function renderCancellation(container, result) {
  const rows = [
    ['Total booking amount', result.totalBookingAmount],
    ['Amount paid', result.amountPaid],
    ['Days remaining before event', result.daysRemaining],
    ['Standard cancellation percentage', `${Number(result.cancellationPercentage).toFixed(2)}%`],
    ['Vendor-specific forfeiture', result.vendorSpecificForfeiture],
    ['Total forfeiture after cap', result.totalForfeiture],
    ['Refund eligible (not transferred)', result.refundAmount],
    ['Additional recoverable amount (not collected)', result.additionalRecoverableAmount]
  ];
  const lines = (result.lines || []).map(line => `<div class="quote-line"><span>${escapeHtml(line.line)} forfeiture</span><strong>${money(line.forfeiture)}</strong></div>`).join('');
  const overrides = (result.vendorSpecificBreakdown || []).map(line => `<div class="quote-line"><span>${escapeHtml(line.vendorName)} (${escapeHtml(line.category)} rule)</span><strong>${money(line.forfeiture)}</strong></div>`).join('');
  container.hidden = false;
  container.innerHTML = `<div class="quote-head"><strong>Cancellation breakdown</strong><span>${escapeHtml(result.bookingId)} · ${escapeHtml(result.cancelDate)}</span></div>${rows.map(([label,value])=>`<div class="quote-line"><span>${escapeHtml(label)}</span><strong>${label==='Days remaining before event'?`${Number(value)} days`:typeof value === 'string' && value.endsWith('%') ? escapeHtml(value) : money(value)}</strong></div>`).join('')}<h4>Vendor line forfeiture</h4>${lines}<h4>Vendor-specific override amounts</h4>${overrides||'<p class="fine-print">No vendor override applied.</p>'}<p class="fine-print">Refund status: ${escapeHtml(result.refundStatus)}. Transfer status: ${escapeHtml(result.refundTransferStatus)}. Any additional recoverable amount has not been collected. This demo does not process bank refunds.</p>`;
}

function renderVenues() {
  const images = [
    'https://images.unsplash.com/photo-1519167758481-83f550bb49b3?auto=format&fit=crop&w=1000&q=80',
    'https://images.unsplash.com/photo-1511795409834-ef04bbd61622?auto=format&fit=crop&w=1000&q=80',
    'https://images.unsplash.com/photo-1464366400600-7168b8af9bc3?auto=format&fit=crop&w=1000&q=80'
  ];
  $('#venue-grid').innerHTML = venues.map((venue, index) => `
    <article class="venue-card">
      <div class="venue-photo"><img src="${images[index % images.length]}" alt="Wedding venue inspiration" loading="lazy" onerror="this.style.display='none'"><span class="venue-number">VENUE 0${index + 1}</span></div>
      <div class="venue-details"><div class="venue-title-row"><h3>${escapeHtml(venue.name)}</h3><span class="capacity">Up to ${venue.capacity.toLocaleString('en-IN')} guests</span></div>
      <p class="venue-location">${venue.slots.map(s => s === 'DAY_AND_NIGHT' ? 'Day + night' : s.toLowerCase()).join(' · ')} celebrations</p>
      <div class="venue-bottom"><div><span class="price-label">WEEKDAY STARTING RATE</span><span class="venue-price">${money(venue.weekdayBase)}</span></div><button class="venue-select" type="button" data-venue="${venue.id}">Choose venue <span>↗</span></button></div></div>
    </article>`).join('');
  $('#venue-grid').querySelectorAll('[data-venue]').forEach(button => button.addEventListener('click', () => {
    venueSelect.value = button.dataset.venue;
    updateVenueHint();
    refreshAvailability();
    $('#booking').scrollIntoView({ behavior: 'smooth' });
    clearNotice();
  }));
}

function renderPackages() {
  packageFields.innerHTML = vendors.map(vendor => {
    const options = Object.entries(vendor.packages).sort((a,b) => Number(a[1])-Number(b[1])).map(([name, price]) => `<option value="${name}">${escapeHtml(name)} — ${money(price)}${vendor.category === 'CATERING' ? ' / plate' : ''}</option>`).join('');
    return `<div class="package-select"><label for="package_${vendor.id}">${escapeHtml(vendor.name)} <span class="category">${vendor.category}</span></label><select id="package_${vendor.id}" name="package_${vendor.id}"><option value="">No package selected</option>${options}</select></div>`;
  }).join('');
}

function updateVenueHint() {
  const venue = venues.find(v => v.id === venueSelect.value);
  const guests = $('#guests');
  if (!venue) { $('#venue-hint').textContent = 'Select a venue to see its capacity and pricing.'; guests.removeAttribute('max'); return; }
  $('#venue-hint').textContent = `Capacity: ${venue.capacity.toLocaleString('en-IN')} guests · Weekend and auspicious-date rates may be higher.`;
  guests.max = venue.capacity;
  if (Number(guests.value) > venue.capacity) guests.value = venue.capacity;
}

function validateForm(show = true) {
  if (!form.reportValidity()) return false;
  const venue = venues.find(v => v.id === venueSelect.value);
  if (!venue) { if (show) showNotice('Please choose a venue.', true); return false; }
  if (Number($('#guests').value) > venue.capacity) { if (show) showNotice(`This venue supports up to ${venue.capacity} guests.`, true); return false; }
  const chosenSlot = $('#slot').value;
  const slotAllowed = chosenSlot === 'DAY_AND_NIGHT' ? venue.slots.includes('DAY') && venue.slots.includes('NIGHT') : venue.slots.includes(chosenSlot);
  if (!slotAllowed) { if (show) showNotice('This venue does not support the selected slot.', true); return false; }
  return true;
}

function renderQuote(quote) {
  quoteBox.hidden = false;
  const rental = quote.venuePricing;
  const rentalBreakdown = rental ? `<div class="venue-pricing-breakdown"><h4>Venue rental calculation</h4><div class="quote-line"><span>Weekday base rental</span><strong>${money(rental.baseWeekdayRental)}</strong></div><div class="quote-line"><span>Date category · applied multiplier</span><strong>${escapeHtml(rental.dateCategory)} · ${Number(rental.multiplier).toFixed(1)}×</strong></div>${rental.slotRentals.map(line => `<div class="quote-line"><span>${escapeHtml(line.slot)} slot${Number(line.discount)>0?' (second slot)':''}</span><strong>${money(line.amount)}</strong></div>`).join('')}<div class="quote-line"><span>Second-slot discount</span><strong>− ${money(rental.secondSlotDiscount)}</strong></div><div class="quote-total"><span>Final venue rental</span><strong>${money(rental.finalVenueRental)}</strong></div></div>` : '';
  quoteBox.innerHTML = `<div class="quote-head"><strong>Your estimate</strong><span>${escapeHtml(quote.date)} · ${escapeHtml(quote.slot.replace('_',' + '))}</span></div><p class="quote-meta">${Number(quote.guests).toLocaleString('en-IN')} guests</p>${rentalBreakdown}
    ${quote.lines.map(line => `<div class="quote-line"><span>${escapeHtml(line.label)}</span><strong>${money(line.amount)}</strong></div>`).join('')}
    <div class="quote-total"><span>Estimated total</span><strong>${money(quote.total)}</strong></div>
    <button type="button" class="button button-outline full print-quote-button">Print / Save quote</button>
    <p class="fine-print">Indicative estimate only. Final charges depend on confirmed terms and actual event details.</p>`;
  quoteBox.querySelector('.print-quote-button').addEventListener('click', () => window.print());
}

$('#quote-button').addEventListener('click', async () => {
  clearNotice();
  if (!validateForm()) return;
  const button = $('#quote-button'); button.disabled = true; button.textContent = 'Calculating…';
  try { renderQuote(await request('/api/quote', { method: 'POST', headers: {'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'}, body: formData() })); }
  catch (error) { showNotice(error.message, true); }
  finally { button.disabled = false; button.innerHTML = 'Calculate my estimate <span>↗</span>'; }
});

form.addEventListener('submit', async (event) => {
  event.preventDefault(); clearNotice();
  if (!validateForm()) return;
  const button = $('#book-button'); button.disabled = true; button.textContent = 'Reserving…';
  try {
    const enquiry = await request('/api/enquiries', { method: 'POST', headers: {'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'}, body: formData() });
    localStorage.setItem('ps108-last-enquiry', enquiry.enquiryId);
    renderQuote(await request('/api/quote', { method: 'POST', headers: {'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'}, body: formData() }));
    renderEnquiry(enquiry);
  } catch (error) { showNotice(error.message, true); button.disabled = false; button.innerHTML = 'Reserve this date <span>→</span>'; }
});

function renderEnquiry(enquiry) {
  const box = $('#quote-box');
  box.hidden = false;
  box.querySelector('.hold-status')?.remove();
  const holdInfo = enquiry.status === 'HELD'
    ? `<p class="fine-print">Held since ${escapeHtml(enquiry.holdCreatedAt)} · expires ${escapeHtml(enquiry.holdExpiresAt)}. Pay the required first advance to confirm.</p>
       <div class="field"><label for="first-advance-amount">Required first advance (simulated)</label><input id="first-advance-amount" name="amount" type="number" min="${Number(enquiry.requiredFirstAdvance)}" step="0.01" value="${Number(enquiry.requiredFirstAdvance)}" required><button id="first-advance-pay" type="button" class="button button-dark full">Pay first advance and confirm</button></div>`
    : enquiry.status === 'QUEUED'
      ? `<p class="fine-print">Queue position: ${Number(enquiry.queuePosition)}. You will get a fresh 72-hour hold if the slot becomes available.</p>`
      : `<p class="fine-print">Enquiry status: ${escapeHtml(enquiry.status)}${enquiry.bookingId ? ` · Booking ${escapeHtml(enquiry.bookingId)}` : ''}</p>`;
  const canCancel = enquiry.status === 'HELD' || enquiry.status === 'QUEUED';
  const displayStatus = enquiry.slotStatus === 'BOOKED' && enquiry.status === 'CANCELLED' ? 'BOOKED' : enquiry.status;
  box.insertAdjacentHTML('afterbegin', `<div class="hold-status"><div class="quote-head"><strong>Enquiry ${escapeHtml(enquiry.enquiryId)} · ${escapeHtml(displayStatus)}</strong><span>${escapeHtml(enquiry.message)}</span></div>${holdInfo}${canCancel?'<button id="cancel-enquiry" type="button" class="button button-outline full">Cancel enquiry</button>':''}</div>`);
  $('#book-button').disabled = canCancel;
  $('#book-button').textContent = canCancel ? `${enquiry.status} · ${enquiry.enquiryId}` : 'Reserve another date →';
  $('#first-advance-pay')?.addEventListener('click', async () => {
    const amountField = $('#first-advance-amount');
    if (!amountField.reportValidity()) return;
    const values = formData();
    values.set('enquiryId', enquiry.enquiryId);
    values.set('amount', amountField.value);
    try {
      const result = await request('/api/confirm', {method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:values});
      box.innerHTML = `<div class="quote-head"><strong>Booking ${escapeHtml(result.bookingId)} confirmed</strong><span>${money(result.amountPaid)} simulated advance paid</span></div><p>${escapeHtml(result.message)} The overlapping enquiry queue has been cleared.</p>`;
      if (enquiryPoll) clearInterval(enquiryPoll);
      localStorage.removeItem('ps108-last-enquiry');
      showNotice(`Booking ${result.bookingId} confirmed after the first advance. Payment is simulated; no bank transaction occurred.`);
      $('#book-button').textContent = `Confirmed · ${result.bookingId}`;
    } catch(error) { showNotice(error.message, true); }
  });
  $('#cancel-enquiry')?.addEventListener('click', async () => {
    try {
      const result = await request('/api/enquiries/cancel', {method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:new URLSearchParams({enquiryId:enquiry.enquiryId})});
      if (enquiryPoll) clearInterval(enquiryPoll);
      localStorage.removeItem('ps108-last-enquiry');
      box.innerHTML = `<p>Enquiry ${escapeHtml(result.enquiryId)} status: ${escapeHtml(result.status)}.</p>`;
      showNotice(`Enquiry ${enquiry.enquiryId} cancelled.`);
      $('#book-button').disabled = false;
      $('#book-button').innerHTML = 'Reserve this date <span>→</span>';
    } catch(error) { showNotice(error.message, true); }
  });
  if (enquiry.status === 'QUEUED' || enquiry.status === 'HELD') {
    if (enquiryPoll) clearInterval(enquiryPoll);
    enquiryPoll = setInterval(async () => {
      try {
        const status = await request(`/api/enquiries/${encodeURIComponent(enquiry.enquiryId)}`);
        if (status.status !== enquiry.status) {
          clearInterval(enquiryPoll);
          const message = status.status === 'HELD' ? 'A 72-hour hold is now active.' : status.slotStatus === 'BOOKED' ? 'Another customer confirmed this date with the first advance. This slot is now booked.' : `Enquiry status changed to ${status.status}.`;
          renderEnquiry({...status, message});
          if (status.status !== 'HELD' && status.status !== 'QUEUED') localStorage.removeItem('ps108-last-enquiry');
        } else if (status.status === 'QUEUED') {
          const heading = box.querySelector('.quote-head');
          if (heading) heading.innerHTML = `<strong>Enquiry ${escapeHtml(status.enquiryId)} · QUEUED</strong><span>Position ${status.queuePosition}</span>`;
        } else {
          const expiry = box.querySelector('.hold-status .fine-print');
          if (expiry) expiry.textContent = `Held since ${status.holdCreatedAt} · expires ${status.holdExpiresAt}. Pay the required first advance to confirm.`;
        }
      } catch (_) { /* Keep the visible queue state; next poll can recover from transient network errors. */ }
    }, 5000);
  }
}

async function refreshAvailability() {
  if (!venueSelect.value || !$('#date').value) return;
  try {
    const params = new URLSearchParams({venueId: venueSelect.value, date: $('#date').value, slot: $('#slot').value});
    const result = await request(`/api/availability?${params}`);
    const venue = venues.find(v => v.id === venueSelect.value);
    $('#venue-hint').textContent = `Capacity: ${venue?.capacity.toLocaleString('en-IN') ?? '—'} guests · ${result.status.replace('_',' ')} for this date and slot.`;
  } catch (error) { $('#venue-hint').textContent = error.message; }
}
venueSelect.addEventListener('change', () => { updateVenueHint(); refreshAvailability(); quoteBox.hidden = true; clearNotice(); });
$('#date').addEventListener('change', refreshAvailability);
$('#slot').addEventListener('change', refreshAvailability);
$('#guests').addEventListener('input', () => { quoteBox.hidden = true; clearNotice(); });
form.addEventListener('change', () => { quoteBox.hidden = true; });

async function init() {
  const date = new Date(); date.setDate(date.getDate() + 60);
  $('#date').min = localISODate(new Date());
  $('#date').value = localISODate(date);
  try {
    [venues, vendors] = await Promise.all([request('/api/venues'), request('/api/vendors')]);
    venueSelect.innerHTML = '<option value="">Select your venue</option>' + venues.map(v => `<option value="${v.id}">${escapeHtml(v.name)} · ${v.capacity} guests</option>`).join('');
    renderVenues(); renderPackages(); updateVenueHint();
    const savedEnquiryId = localStorage.getItem('ps108-last-enquiry');
    if (savedEnquiryId) {
      try {
        const saved = await request(`/api/enquiries/${encodeURIComponent(savedEnquiryId)}`);
        renderEnquiry({...saved, message: saved.status === 'HELD' ? 'Your 72-hour hold is active.' : saved.slotStatus === 'BOOKED' ? 'This slot has been confirmed as booked.' : `Your enquiry is ${saved.status.toLowerCase()}.`});
      } catch (_) { localStorage.removeItem('ps108-last-enquiry'); }
    }
  } catch (error) {
    $('#venue-grid').innerHTML = '<div class="loading-card">Could not connect to the Java server. Please restart WebMain and refresh this page.</div>';
    showNotice(error.message, true);
  }
}

const cancellationForm = $('#customer-cancel-form');
const cancellationDate = $('#customer-cancel-date');
cancellationDate.value = localISODate(new Date());
cancellationForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!window.confirm('Cancel this booking? The result will show the calculated forfeiture and refund eligibility. No money will be transferred.')) return;
  const button = $('#customer-cancel-button');
  const resultBox = $('#customer-cancel-result');
  const noticeBox = $('#customer-cancel-notice');
  button.disabled = true;
  noticeBox.hidden = true;
  try {
    const result = await request('/api/cancel', {method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:new URLSearchParams(new FormData(cancellationForm))});
    renderCancellation(resultBox, result);
    noticeBox.hidden = false;
    noticeBox.className = 'notice';
    noticeBox.textContent = result.message;
  } catch (error) {
    noticeBox.hidden = false;
    noticeBox.className = 'notice error';
    noticeBox.textContent = error.message;
  } finally { button.disabled = false; }
});
init();
