import copy
import http.cookiejar
import http.server
import json
import os
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch
try:
    from . import campus_onboarding as onboarding
    from . import uno_q_server as legacy
except ImportError:
    import campus_onboarding as onboarding
    import uno_q_server as legacy


def complete_document(name='Test college'):
    d=onboarding.blank_document('https://campus.example/')
    d.update(campus_name=name,short_name='TEST',base_location={'name':'Gate','latitude':10.,'longitude':20.})
    d['buildings']=[{'code':'B','name':'Teaching block','latitude':10.,'longitude':20.,'total_floors':3,
        'rooms':[{'room_id':'201','name':'Classroom','floor':2,'category':'classroom'}]}]
    d['sections']=[{'section_id':'A','name':'Group A','department':'Engineering','default_building_code':'B','default_room_id':'201','schedule':{'MONDAY':[
        {'start_time':'09:00','end_time':'10:00','course_code':'CS101','course_name':'Computing','room_id':'201','building_code':'B','period':1,'is_lab':False}]}}]
    return onboarding.normalize(d)


class OnboardingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory()
        legacy.DB_PATH=os.path.join(cls.tmp.name,'test.db')
        cls.env=patch.dict(os.environ,{'PUBLIC_CAMPUS_REGISTRY_URL':'','PRISM_ADMIN_PASSWORD':'test-administrator-password'})
        cls.env.start();legacy.init_db();cls.service=onboarding.Service(legacy)
        cls.server=http.server.ThreadingHTTPServer(('127.0.0.1',0),onboarding.enhance(legacy))
        cls.thread=threading.Thread(target=cls.server.serve_forever,daemon=True);cls.thread.start()
        cls.base='http://127.0.0.1:'+str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown();cls.server.server_close();cls.env.stop();cls.tmp.cleanup()

    def create_draft(self):
        with patch.object(onboarding,'public_url',side_effect=lambda x:x),patch.object(onboarding,'fetch_site',return_value=('<title>Test college</title>','https://campus.example/','html')):
            d=self.service.create('https://campus.example/')
            end=time.monotonic()+2
            while d['state']=='scanning' and time.monotonic()<end:
                time.sleep(.01);d=self.service.draft(d['id'])
            return d

    def test_ai_context_and_fallback_are_campus_isolated(self):
        d=self.create_draft()
        doc=complete_document('IIT Guwahati test')
        doc['buildings'][0]['name']='Core 5 test block'
        self.service.save(d['id'],doc,d['revision'])
        cid=self.service.publish(d['id'])['campus_id']
        context=legacy.campus_ai_context(cid)
        self.assertIn('IIT Guwahati test',context)
        self.assertNotIn('MSRIT',context)
        answer=legacy.campus_record_answer(cid,'Where is Core 5 test block?')
        self.assertIn('Core 5 test block',answer)
        self.assertNotIn('Apex',answer)
        self.assertNotIn('MSRIT',legacy.campus_record_answer(cid,'what is job'))
        self.assertNotIn('LPA',legacy.campus_record_answer(cid,'what is job'))
        with self.assertRaises(ValueError):legacy.campus_ai_context('UNKNOWN_CAMPUS')

    def test_missing_facts_are_questions_not_invented(self):
        d=self.create_draft();v=d['validation']
        self.assertFalse(v['ready']);self.assertTrue(v['questions']);self.assertEqual([],d['document']['buildings'])
        with self.assertRaises(ValueError):self.service.publish(d['id'])

    def test_delete_draft_preserves_published_campus_and_can_resume_editing(self):
        d=self.create_draft();d=self.service.save(d['id'],complete_document(),d['revision'])
        before=self.service.publish(d['id'])['pack'];cid=before['campus_id']
        result=self.service.delete_draft(d['id'])
        self.assertTrue(result['published_campus_preserved'])
        with self.assertRaises(KeyError):self.service.draft(d['id'])
        self.assertEqual(before,self.service.package(cid))
        restored=self.service.edit_campus(cid)
        self.assertEqual(cid,restored['campus_id'])
        self.assertEqual(before['buildings'],restored['document']['buildings'])
        restored=self.service.save(restored['id'],restored['document'],restored['revision'])
        self.assertEqual(cid,self.service.publish(restored['id'])['campus_id'])

    def test_delete_unpublished_draft_and_operations_qr_are_local(self):
        d=self.create_draft();self.service.delete_draft(d['id'])
        with self.assertRaises(KeyError):self.service.draft(d['id'])
        self.assertIn('src="/qrcode.js"',legacy.HTML_DASHBOARD)
        self.assertNotIn('cdn.jsdelivr.net',legacy.HTML_DASHBOARD)
        self.assertIn('populateTimetableFilters()',legacy.HTML_DASHBOARD)
        self.assertIn('try { renderQrCode(); } catch',legacy.HTML_DASHBOARD)

    def test_old_mirror_missing_timetable_is_repaired_without_overwriting_admin_edit(self):
        d=self.create_draft();self.service.save(d['id'],complete_document(),d['revision'])
        cid=self.service.publish(d['id'])['campus_id']
        with legacy.get_db() as db:
            db.execute('DELETE FROM college_timetables WHERE campus_id=?',(cid,))
            db.execute('UPDATE buildings SET name="Administrator edit" WHERE campus_id=?',(cid,))
        onboarding.Service(legacy)
        with legacy.get_db() as db:
            self.assertEqual(1,db.execute('SELECT COUNT(*) FROM college_timetables WHERE campus_id=?',(cid,)).fetchone()[0])
            self.assertEqual('Administrator edit',db.execute('SELECT name FROM buildings WHERE campus_id=?',(cid,)).fetchone()[0])

    def test_complete_campus_unique_persistent_id_and_snapshot(self):
        d=self.create_draft();doc=complete_document()
        d=self.service.save(d['id'],doc,d['revision']);result=self.service.publish(d['id'])
        cid=result['campus_id'];self.assertTrue(cid.startswith('TEST_'))
        self.assertEqual(cid,self.service.publish(d['id'])['campus_id'])
        self.assertEqual(cid,onboarding.Service(legacy).package(cid)['campus_id'])
        doc['campus_name']='Draft-only change';self.service.save(d['id'],doc,d['revision'])
        self.assertEqual('Test college',self.service.package(cid)['campus_name'])
        self.assertEqual(2,result['pack']['sections'][0]['default_floor'])
        with self.assertRaises(KeyError):self.service.draft('not-a-draft')

    def test_invalid_coordinates_block_publication_but_draft_is_recoverable(self):
        d=self.create_draft();doc=complete_document();doc['buildings'][0]['latitude']=200
        d=self.service.save(d['id'],doc,d['revision']);self.assertTrue(d['validation']['errors'])
        with self.assertRaises(ValueError):self.service.publish(d['id'])

    def test_stale_edits_are_rejected(self):
        d=self.create_draft();self.service.save(d['id'],complete_document(),d['revision'])
        with self.assertRaises(ValueError):self.service.save(d['id'],complete_document(),d['revision'])

    def test_csv_preview_and_retry_do_not_duplicate_classes(self):
        d=complete_document();text='section_id,day,start_time,end_time,course_code,course_name,building_code,room_id\nA,MONDAY,10:00,11:00,CS102,Math,B,201\n'
        onboarding.import_csv(d,text);onboarding.import_csv(d,text)
        self.assertEqual(2,len(d['sections'][0]['schedule']['MONDAY']))
        self.assertTrue(onboarding.validate(d)['ready'])
        with self.assertRaises(ValueError):onboarding.import_csv(d,'bad,header\nx,y')

    def test_booking_conflict_requires_review(self):
        d=complete_document();d['sections'][0]['schedule']['MONDAY'].append(copy.deepcopy(d['sections'][0]['schedule']['MONDAY'][0]))
        self.assertTrue(onboarding.validate(d)['warnings'])
        draft=self.create_draft();draft=self.service.save(draft['id'],d,draft['revision'])
        with self.assertRaises(ValueError):self.service.publish(draft['id'])
        self.assertTrue(self.service.publish(draft['id'],True)['campus_id'])

    def test_publication_and_campus_data_are_isolated(self):
        a=self.create_draft();b=self.create_draft()
        a=self.service.save(a['id'],complete_document('One'),a['revision'])
        b=self.service.save(b['id'],complete_document('Two'),b['revision'])
        ca=self.service.publish(a['id'])['campus_id'];cb=self.service.publish(b['id'])['campus_id']
        self.assertNotEqual(ca,cb);self.assertEqual('One',self.service.package(ca)['campus_name'])
        self.assertEqual('Two',self.service.package(cb)['campus_name'])
        with self.assertRaises(ValueError):self.service.save(a['id'],dict(complete_document(),campus_id=cb),a['revision'])
        self.assertIsNone(self.service.package('UNKNOWN'))
        self.assertEqual('MSRIT',legacy.compile_campus_pack('MSRIT')['campus_id'])

    def test_private_network_websites_are_rejected(self):
        for url in ('file:///etc/passwd','http://user:pass@example.com/','http://127.0.0.1/','http://169.254.169.254/'):
            with self.assertRaises(ValueError):onboarding.public_url(url)

    def test_unverified_labels_survive_import_and_publication(self):
        d=self.create_draft();doc=complete_document()
        doc['buildings'][0]['rooms'][0]['floor_label']='Floor unverified'
        d=self.service.save(d['id'],doc,d['revision'])
        result=self.service.publish(d['id'])
        self.assertEqual('Floor unverified',result['pack']['buildings'][0]['rooms'][0]['floor_label'])
        compiled=legacy.compile_campus_pack(result['campus_id'])
        self.assertEqual(1,len(compiled['buildings']))
        self.assertEqual('Floor unverified',compiled['buildings'][0]['rooms'][0]['floor_label'])

    def test_repeat_report_does_not_add_supporters(self):
        report={'category':'Electrical','building':'Core 5','room':'5405','description':'Fan not working'}
        legacy.ingest_raw_reports_db('MSRIT',[report],'installation-one')
        legacy.ingest_raw_reports_db('MSRIT',[report],'installation-one')
        with legacy.get_db() as db:
            row=db.execute('SELECT report_count FROM issue_reports WHERE cluster_key=?',
                           (legacy.normalize_cluster_key(report),)).fetchone()
        self.assertEqual(1,row[0])

    def test_html_candidates_preserve_sources_and_slot_letters(self):
        doc=onboarding.blank_document();page=onboarding.Page('<title>Public college</title><a href="/cse/">Computer Science</a><a href="/timetable.pdf">Class timetable</a><table><tr><td>CS101</td><td>Computing</td><td>A</td><td>201</td></tr></table>','https://campus.example/')
        onboarding.parse_page(doc,page)
        self.assertEqual('A',doc['discovered_courses'][0]['slot']);self.assertEqual([],doc['sections'])
        self.assertEqual('https://campus.example/',doc['discovered_courses'][0]['source_url'])
        self.assertTrue(doc['departments']);self.assertTrue(doc['timetable_sources'])

    def test_http_auth_and_public_student_compatibility(self):
        with self.assertRaises(urllib.error.HTTPError) as error:urllib.request.urlopen(self.base+'/api/onboarding/state')
        self.assertEqual(401,error.exception.code)
        opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        body=json.dumps({'username':'admin','password':'test-administrator-password'}).encode()
        with opener.open(urllib.request.Request(self.base+'/api/onboarding/login',data=body,headers={'Content-Type':'application/json'})) as r:self.assertEqual(200,r.status)
        with opener.open(self.base+'/api/onboarding/state') as r:self.assertIn('drafts',json.load(r))
        with self.assertRaises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(urllib.request.Request(self.base+'/api/onboarding/delete',data=b'{"id":"no-draft"}',headers={'Content-Type':'application/json'}))
        self.assertEqual(401,error.exception.code)
        with opener.open(self.base+'/operations') as r:self.assertIn(b'Announcements & Class Updates',r.read())
        with urllib.request.urlopen(self.base+'/qrcode.js') as r:self.assertIn(b'MIT license',r.read())
        with self.assertRaises(urllib.error.HTTPError):urllib.request.urlopen(urllib.request.Request(self.base+'/api/admin/campus',data=b'{}',headers={'Content-Type':'application/json'}))
        with urllib.request.urlopen(self.base+'/api/campus/MSRIT/package') as r:self.assertEqual('MSRIT',json.load(r)['campus_id'])
        with opener.open(self.base+'/') as r:self.assertIn(b'Campus studio',r.read())


if __name__=='__main__':unittest.main(verbosity=2)
